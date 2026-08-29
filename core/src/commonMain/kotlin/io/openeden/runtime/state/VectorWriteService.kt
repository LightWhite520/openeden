package io.openeden.runtime.state

import io.openeden.runtime.affect.ShockState
import io.openeden.runtime.affect.ShockStateEngine
import io.openeden.runtime.affect.ShockSignal
import io.openeden.runtime.inference.DirectInferenceExecutor
import io.openeden.runtime.inference.InferenceExecutor
import io.openeden.runtime.incarnation.IncarnationMutexRegistry
import io.openeden.runtime.incarnation.IncarnationState
import io.openeden.runtime.incarnation.IncarnationStateStore
import io.openeden.runtime.incarnation.IncarnationTurnGate
import io.openeden.persona.PersonaMode
import io.openeden.persona.PersonaSubState
import io.openeden.memory.MemoryEntry
import io.openeden.runtime.session.SessionMutexRegistry
import io.openeden.runtime.session.SessionState
import io.openeden.runtime.session.SessionStateStore
import io.openeden.transcript.AtomicTurnCommitStore
import io.openeden.transcript.ConversationTurn
import io.openeden.transcript.TurnCommitOutcome
import io.openeden.transcript.TurnPostCommitPlan

import io.openeden.bio.BioVector
import io.openeden.bio.VectorDelta
import io.openeden.trace.TraceTag
import kotlinx.coroutines.sync.withLock

class VectorWriteService private constructor(
    private val store: SessionStateStore?,
    val mutexRegistry: SessionMutexRegistry,
    private val incarnationStore: IncarnationStateStore?,
    val incarnationMutexRegistry: IncarnationMutexRegistry,
    private val inferenceExecutor: InferenceExecutor,
    private val vectorDeltaReducer: VectorDeltaReducer,
    private val backgroundDynamicsReducer: BackgroundDynamicsReducer,
    private val backgroundDynamicsReducerFactory: ((String) -> BackgroundDynamicsReducer)?,
    @Suppress("UNUSED_PARAMETER") constructorMarker: Unit,
) {
    constructor() : this(store = null)

    constructor(
        store: SessionStateStore? = null,
        mutexRegistry: SessionMutexRegistry = SessionMutexRegistry(),
        incarnationStore: IncarnationStateStore? = null,
        incarnationMutexRegistry: IncarnationMutexRegistry = IncarnationMutexRegistry(),
        inferenceExecutor: InferenceExecutor = DirectInferenceExecutor,
        vectorDeltaReducer: VectorDeltaReducer = VectorDeltaReducer(),
        backgroundDynamicsReducer: BackgroundDynamicsReducer = BackgroundDynamicsReducer.stationary(),
    ) : this(
        store = store,
        mutexRegistry = mutexRegistry,
        incarnationStore = incarnationStore,
        incarnationMutexRegistry = incarnationMutexRegistry,
        inferenceExecutor = inferenceExecutor,
        vectorDeltaReducer = vectorDeltaReducer,
        backgroundDynamicsReducer = backgroundDynamicsReducer,
        backgroundDynamicsReducerFactory = null,
        constructorMarker = Unit,
    )

    constructor(
        store: SessionStateStore? = null,
        mutexRegistry: SessionMutexRegistry = SessionMutexRegistry(),
        incarnationStore: IncarnationStateStore? = null,
        incarnationMutexRegistry: IncarnationMutexRegistry = IncarnationMutexRegistry(),
        inferenceExecutor: InferenceExecutor = DirectInferenceExecutor,
        vectorDeltaReducer: VectorDeltaReducer = VectorDeltaReducer(),
        backgroundDynamicsReducer: BackgroundDynamicsReducer = BackgroundDynamicsReducer.stationary(),
        backgroundDynamicsReducerFactory: ((String) -> BackgroundDynamicsReducer)?,
    ) : this(
        store = store,
        mutexRegistry = mutexRegistry,
        incarnationStore = incarnationStore,
        incarnationMutexRegistry = incarnationMutexRegistry,
        inferenceExecutor = inferenceExecutor,
        vectorDeltaReducer = vectorDeltaReducer,
        backgroundDynamicsReducer = backgroundDynamicsReducer,
        backgroundDynamicsReducerFactory = backgroundDynamicsReducerFactory,
        constructorMarker = Unit,
    )

    val incarnationMutationGate = IncarnationTurnGate(incarnationMutexRegistry)

    internal fun isBackedBy(candidate: SessionStateStore): Boolean = store === candidate

    internal fun isBackedBy(candidate: IncarnationStateStore): Boolean = incarnationStore === candidate

    suspend fun applyLlmDelta(
        sessionId: String,
        preTickedSnapshot: BioVector,
        delta: VectorDelta,
    ): VectorWriteResult<SessionState> {
        val mutex = mutexRegistry.forSession(sessionId)
        return mutex.withLock { applyLlmDeltaLocked(sessionId, preTickedSnapshot, delta) }
    }

    suspend fun applyLlmDeltaLocked(
        sessionId: String,
        preTickedSnapshot: BioVector,
        delta: VectorDelta,
    ): VectorWriteResult<SessionState> {
        val latest = sessionStore.read(sessionId)
        val updatedVector = inferenceExecutor.run {
            val relativePreTickDelta = latest.vector.deltaTo(preTickedSnapshot)
            latest.vector.apply(relativePreTickDelta).apply(delta)
        }
        val updated = latest.copy(
            vector = updatedVector,
            evolutionIndex = latest.evolutionIndex + 1,
        )
        sessionStore.write(updated)
        return VectorWriteResult(
            state = updated,
            traceTags = setOf(TraceTag.VectorWriteSerialized),
        )
    }

    suspend fun commitTurnLocked(
        sessionId: String,
        preTickedSnapshot: BioVector,
        originSnapshot: BioVector,
        delta: VectorDelta,
        shock: ShockState?,
        shockSignal: ShockSignal? = null,
        lastUserActivityMs: Long?,
        turn: ConversationTurn? = null,
    ): VectorWriteResult<SessionState> {
        val latest = sessionStore.read(sessionId)
        val updatedVector = inferenceExecutor.run {
            val relativePreTickDelta = latest.vector.deltaTo(preTickedSnapshot)
            latest.vector.apply(relativePreTickDelta).apply(delta)
        }
        val shockMerge = shockSignal?.let { signal ->
            inferenceExecutor.run { ShockStateEngine.merge(latest.shockState, signal) }
        }
        val mergedShock = shockMerge?.state ?: shock ?: latest.shockState
        val updatedOmega = when {
            shockMerge?.activated == true -> inferenceExecutor.run {
                latest.omega.increase(shockMerge.state.intensity * 0.15f)
            }
            shock != null -> inferenceExecutor.run { ShockStateEngine.omegaJump(latest.omega, shock) }
            else -> latest.omega
        }
        val updated = latest.copy(
            vector = updatedVector,
            origin = originSnapshot,
            evolutionIndex = latest.evolutionIndex + 1,
            shockState = mergedShock,
            omega = updatedOmega,
            lastUserActivityMs = lastUserActivityMs ?: latest.lastUserActivityMs,
        )
        val turnCommitOutcome = if (turn != null) {
            val atomicStore = sessionStore as? AtomicTurnCommitStore
                ?: error("Public turns require an atomic turn commit store")
            atomicStore.writeCommittedTurn(updated, turn)
        } else {
            sessionStore.write(updated)
            null
        }
        val committedState = if (turnCommitOutcome != null) sessionStore.read(sessionId) else updated
        return VectorWriteResult(
            state = committedState,
            traceTags = if (turnCommitOutcome == TurnCommitOutcome.ALREADY_COMMITTED) {
                setOf(TraceTag.TranscriptRetry)
            } else {
                buildSet {
                    add(TraceTag.VectorWriteSerialized)
                    if (shock != null || shockSignal != null) add(TraceTag.ShockStateTransition)
                }
            },
            turnCommitOutcome = turnCommitOutcome,
        )
    }

    /** Mutex-guarded read-modify-write for session mutations that are not LLM vector deltas
     *  (e.g. activity timestamps, shock-heartbeat flag). Shares the per-session Mutex with
     *  [applyLlmDelta] so all writes to a session remain serialized (§14.2). */
    suspend fun update(sessionId: String, transform: (SessionState) -> SessionState): SessionState {
        val mutex = mutexRegistry.forSession(sessionId)
        return mutex.withLock { updateLocked(sessionId, transform) }
    }

    suspend fun updateLocked(sessionId: String, transform: (SessionState) -> SessionState): SessionState {
        val updated = transform(sessionStore.read(sessionId))
        sessionStore.write(updated)
        return updated
    }

    /** Record the timestamp of a USER turn (never called for heartbeat turns). */
    suspend fun markUserActivity(sessionId: String, nowMs: Long): SessionState =
        update(sessionId) { it.copy(lastUserActivityMs = nowMs) }

    suspend fun markUserActivityLocked(sessionId: String, nowMs: Long): SessionState =
        updateLocked(sessionId) { it.copy(lastUserActivityMs = nowMs) }

    /** Latch the one-shot shock-extended heartbeat flag for the current ShockState activation. */
    suspend fun markShockHeartbeatFired(sessionId: String): SessionState =
        update(sessionId) { state ->
            state.copy(shockState = state.shockState?.copy(shockHeartbeatFired = true))
        }

    suspend fun markShockHeartbeatFiredLocked(sessionId: String): SessionState =
        updateLocked(sessionId) { state ->
            state.copy(shockState = state.shockState?.copy(shockHeartbeatFired = true))
        }

    suspend fun applyShock(sessionId: String, signal: ShockSignal): VectorWriteResult<SessionState> {
        val mutex = mutexRegistry.forSession(sessionId)
        return mutex.withLock { applyShockSignalLocked(sessionId, signal) }
    }

    suspend fun applyShockSignalLocked(sessionId: String, signal: ShockSignal): VectorWriteResult<SessionState> {
        val latest = sessionStore.read(sessionId)
        val (merge, updatedOmega) = inferenceExecutor.run {
            val result = ShockStateEngine.merge(latest.shockState, signal)
            val omega = if (result.activated) {
                latest.omega.increase(result.state.intensity * 0.15f)
            } else {
                latest.omega
            }
            result to omega
        }
        val updated = latest.copy(
            shockState = merge.state,
            omega = updatedOmega,
        )
        sessionStore.write(updated)
        return VectorWriteResult(
            state = updated,
            traceTags = setOf(TraceTag.ShockStateTransition),
        )
    }

    suspend fun applyBackgroundDrift(sessionId: String, delta: VectorDelta): VectorWriteResult<SessionState> {
        val mutex = mutexRegistry.forSession(sessionId)
        return mutex.withLock {
            val latest = sessionStore.read(sessionId)
            val updated = inferenceExecutor.run {
                latest.copy(vector = latest.vector.apply(delta))
            }
            sessionStore.write(updated)
            VectorWriteResult(
                state = updated,
                traceTags = setOf(TraceTag.BackgroundDrift),
            )
        }
    }

    suspend fun applyRuntimeTick(
        sessionId: String,
        transform: suspend (SessionState) -> Pair<SessionState, Set<String>>,
    ): VectorWriteResult<SessionState> {
        val mutex = mutexRegistry.forSession(sessionId)
        return mutex.withLock {
            val latest = sessionStore.read(sessionId)
            val (updated, traceTags) = transform(latest)
            sessionStore.write(updated)
            VectorWriteResult(
                state = updated,
                traceTags = traceTags,
            )
        }
    }

    private fun BioVector.deltaTo(target: BioVector): VectorDelta = VectorDelta(
        l = target.l - l,
        p = target.p - p,
        e = target.e - e,
        s = target.s - s,
        tau = target.tau - tau,
        v = target.v - v,
        m = target.m - m,
        f = target.f - f,
    )

    suspend fun commitIncarnationTurn(
        incarnationId: String,
        preTickedSnapshot: BioVector,
        baseSnapshot: BioVector = preTickedSnapshot,
        delta: VectorDelta,
        shockSignal: ShockSignal?,
        lastUserActivityMs: Long?,
        reductionContext: VectorDeltaContext = VectorDeltaContext.Ordinary,
        reductionAtMs: Long? = null,
        turn: ConversationTurn? = null,
        postCommitPlan: TurnPostCommitPlan? = null,
        finalizePreparedMemory: Boolean = true,
    ): VectorWriteResult<IncarnationState> = incarnationMutationGate.withIncarnation(incarnationId) {
        val latest = bioStore.read(incarnationId)
        val previousDynamicsAtMs = maxOfNullable(latest.lastVectorDynamicsAtMs, latest.lastRuntimeTickAtMs)
        val reductionOrigin = latest.origin
        val (backgroundReduction, vectorReduction) = inferenceExecutor.run {
            val background = backgroundDynamicsReducerFor(incarnationId).reduce(
                vector = latest.vector,
                shockState = latest.shockState,
                omega = latest.omega,
                previousAtMs = previousDynamicsAtMs,
                throughAtMs = reductionAtMs,
            )
            val effectiveContext = authoritativeContext(
                requested = reductionContext,
                persistedShock = background.shockState,
                signal = shockSignal,
            )
            // Rebase this turn's pre-tick effect onto the latest serialized Bio state.
            val preTickDelta = baseSnapshot.deltaTo(preTickedSnapshot)
            val rebased = background.vector.apply(preTickDelta)
            background to vectorDeltaReducer.reduce(
                current = rebased,
                origin = reductionOrigin,
                proposedDelta = delta,
                context = effectiveContext,
                elapsedSeconds = background.elapsedMillis.toFloat() / 1_000.0f,
            )
        }
        val authoritativeReduction = vectorReduction.copy(
            committedDelta = latest.vector.deltaTo(vectorReduction.result),
        )
        val persistedOrigin = latest.origin.takeIf { it.isValidStorageVector() }
            ?: authoritativeReduction.result
        val shockMerge = shockSignal?.let { signal ->
            inferenceExecutor.run { ShockStateEngine.merge(backgroundReduction.shockState, signal) }
        }
        val updated = latest.copy(
            vector = authoritativeReduction.result,
            origin = persistedOrigin,
            evolutionIndex = latest.evolutionIndex + 1,
            shockState = shockMerge?.state ?: backgroundReduction.shockState,
            omega = if (shockMerge?.activated == true) {
                inferenceExecutor.run { backgroundReduction.omega.increase(shockMerge.state.intensity * 0.15f) }
            } else {
                backgroundReduction.omega
            },
            lastUserActivityMs = maxOfNullable(latest.lastUserActivityMs, lastUserActivityMs),
            lastVectorDynamicsAtMs = backgroundReduction.consumedAtMs,
        )
        val outcome = if (turn == null) {
            require(postCommitPlan == null) { "Post-commit plan requires a committed transcript turn" }
            bioStore.write(updated)
            null
        } else {
            val committedPlan = requireNotNull(postCommitPlan).let { plan ->
                plan.copy(
                    rawMemory = plan.rawMemory?.let { memory ->
                        if (finalizePreparedMemory) {
                            memory.withCommittedState(updated, authoritativeReduction.committedDelta)
                        } else {
                            memory
                        }
                    },
                )
            }
            bioStore.writeCommittedTurn(updated, turn, committedPlan)
        }
        val committed = if (outcome == TurnCommitOutcome.ALREADY_COMMITTED) bioStore.read(incarnationId) else updated
        VectorWriteResult(
            state = committed,
            traceTags = if (outcome == TurnCommitOutcome.ALREADY_COMMITTED) {
                setOf(TraceTag.TranscriptRetry)
            } else {
                buildSet {
                    add(TraceTag.VectorWriteSerialized)
                    add(TraceTag.VectorDeltaReduced)
                    addAll(backgroundReduction.traceTags())
                    if (authoritativeReduction.reasons.any { it.endsWith("rejected") }) {
                        add(TraceTag.VectorDeltaRejected)
                    }
                    if (shockSignal != null) add(TraceTag.ShockStateTransition)
                }
            },
            turnCommitOutcome = outcome,
            vectorReduction = authoritativeReduction.takeUnless { outcome == TurnCommitOutcome.ALREADY_COMMITTED },
        )
    }

    private fun authoritativeContext(
        requested: VectorDeltaContext,
        persistedShock: ShockState?,
        signal: ShockSignal?,
    ): VectorDeltaContext {
        val requestedConfidence = (requested as? VectorDeltaContext.Authoritative)?.confidence
        val persistedShockConfidence = persistedShock
            ?.takeIf { it.active }
            ?.intensity
        val confidence = listOfNotNull(requestedConfidence, persistedShockConfidence, signal?.intensity)
            .filter { it.isFinite() && it in 0.0f..1.0f }
            .maxOrNull()
        return confidence?.let(VectorDeltaContext::Authoritative) ?: VectorDeltaContext.Ordinary
    }

    private fun BioVector.isValidStorageVector(): Boolean =
        toList().all { it.isFinite() && it in 0.0f..1.0f }

    suspend fun readOrCreateIncarnation(
        incarnationId: String,
        personaMode: PersonaMode,
        personaStartSubState: PersonaSubState,
    ): IncarnationState = incarnationMutationGate.withIncarnation(incarnationId) {
        bioStore.readOrCreate(incarnationId, personaMode, personaStartSubState)
    }

    suspend fun updateIncarnation(
        incarnationId: String,
        transform: (IncarnationState) -> IncarnationState,
    ): IncarnationState = incarnationMutationGate.withIncarnation(incarnationId) {
        val updated = transform(bioStore.read(incarnationId))
        bioStore.write(updated)
        updated
    }

    suspend fun reserveCentroidRevision(incarnationId: String): Long =
        incarnationMutationGate.withIncarnation(incarnationId) {
            val latest = bioStore.read(incarnationId)
            check(latest.centroidRevision < Long.MAX_VALUE) { "Centroid revision exhausted" }
            val revision = latest.centroidRevision + 1L
            bioStore.write(latest.copy(centroidRevision = revision))
            revision
        }

    suspend fun applyCentroidCandidate(
        incarnationId: String,
        candidateRevision: Long,
        candidateOrigin: BioVector,
    ): VectorWriteResult<IncarnationState> = incarnationMutationGate.withIncarnation(incarnationId) {
        val latest = bioStore.read(incarnationId)
        val accepted = candidateOrigin.isValidStorageVector() &&
            candidateRevision in 1L..latest.centroidRevision &&
            candidateRevision > latest.originRevision
        val updated = if (accepted) {
            latest.copy(
                origin = candidateOrigin,
                originRevision = candidateRevision,
            ).also { bioStore.write(it) }
        } else {
            latest
        }
        VectorWriteResult(
            state = updated,
            traceTags = if (accepted) setOf(TraceTag.CentroidUpdated) else emptySet(),
            traceAttributes = mapOf(
                "candidate_revision" to candidateRevision.toString(),
                "issued_revision" to latest.centroidRevision.toString(),
                "previous_origin_revision" to latest.originRevision.toString(),
                "origin_revision" to updated.originRevision.toString(),
                "accepted" to accepted.toString(),
            ),
        )
    }

    private fun maxOfNullable(first: Long?, second: Long?): Long? = when {
        first == null -> second
        second == null -> first
        else -> maxOf(first, second)
    }

    private fun MemoryEntry.withCommittedState(
        state: IncarnationState,
        committedDelta: VectorDelta,
    ): MemoryEntry {
        val stableTags = setOf("daily", "stable")
        val isStable = state.omega.value < 0.75f &&
            committedDelta.toList().all { kotlin.math.abs(it) <= 0.05f }
        return copy(
            tags = (tags - stableTags) + if (isStable) stableTags else emptySet(),
            metadata = metadata.copy(
                snapshot8D = state.vector,
                omegaState = state.omega.value,
                deltaVec = committedDelta,
                snapshotOrigin = state.origin,
            ),
        )
    }

    suspend fun markUserActivityForIncarnation(incarnationId: String, nowMs: Long): IncarnationState =
        updateIncarnation(incarnationId) { state -> state.copy(lastUserActivityMs = nowMs) }

    suspend fun markShockHeartbeatFiredForIncarnation(incarnationId: String): IncarnationState =
        updateIncarnation(incarnationId) { state ->
            state.copy(shockState = state.shockState?.copy(shockHeartbeatFired = true))
        }

    suspend fun applyShockForIncarnation(
        incarnationId: String,
        signal: ShockSignal,
    ): VectorWriteResult<IncarnationState> = incarnationMutationGate.withIncarnation(incarnationId) {
        val latest = bioStore.read(incarnationId)
        val merge = inferenceExecutor.run { ShockStateEngine.merge(latest.shockState, signal) }
        val updated = latest.copy(
            shockState = merge.state,
            omega = if (merge.activated) {
                inferenceExecutor.run { latest.omega.increase(merge.state.intensity * 0.15f) }
            } else {
                latest.omega
            },
        )
        bioStore.write(updated)
        VectorWriteResult(updated, setOf(TraceTag.ShockStateTransition))
    }

    suspend fun applyBackgroundDriftForIncarnation(
        incarnationId: String,
        delta: VectorDelta,
    ): VectorWriteResult<IncarnationState> = incarnationMutationGate.withIncarnation(incarnationId) {
        val latest = bioStore.read(incarnationId)
        val updated = inferenceExecutor.run { latest.copy(vector = latest.vector.apply(delta)) }
        bioStore.write(updated)
        VectorWriteResult(updated, setOf(TraceTag.BackgroundDrift))
    }

    suspend fun applyRuntimeTickForIncarnation(
        incarnationId: String,
        transform: suspend (IncarnationState) -> Pair<IncarnationState, Set<String>>,
    ): VectorWriteResult<IncarnationState> = incarnationMutationGate.withIncarnation(incarnationId) {
        val (updated, traceTags) = transform(bioStore.read(incarnationId))
        bioStore.write(updated)
        VectorWriteResult(updated, traceTags)
    }

    suspend fun applyBackgroundDynamicsTickForIncarnation(
        incarnationId: String,
        nowMs: Long,
    ): VectorWriteResult<IncarnationState> = incarnationMutationGate.withIncarnation(incarnationId) {
        val latest = bioStore.read(incarnationId)
        val previousDynamicsAtMs = maxOfNullable(latest.lastVectorDynamicsAtMs, latest.lastRuntimeTickAtMs)
        val background = inferenceExecutor.run {
            backgroundDynamicsReducerFor(incarnationId).reduce(
                vector = latest.vector,
                shockState = latest.shockState,
                omega = latest.omega,
                previousAtMs = previousDynamicsAtMs,
                throughAtMs = nowMs,
            )
        }
        val updated = latest.copy(
            vector = background.vector,
            shockState = background.shockState,
            omega = background.omega,
            lastRuntimeTickAtMs = nowMs,
            lastVectorDynamicsAtMs = background.consumedAtMs,
        )
        bioStore.write(updated)
        VectorWriteResult(
            state = updated,
            traceTags = if (background.baseline) {
                setOf(TraceTag.RuntimeTickBaseline)
            } else {
                background.traceTags(includeBackground = true)
            },
        )
    }

    private val sessionStore: SessionStateStore
        get() = checkNotNull(store) { "Session-scoped vector writes are not configured" }

    private val bioStore: IncarnationStateStore
        get() = checkNotNull(incarnationStore) { "Incarnation-scoped vector writes are not configured" }

    private fun backgroundDynamicsReducerFor(incarnationId: String): BackgroundDynamicsReducer =
        backgroundDynamicsReducerFactory?.invoke(incarnationId) ?: backgroundDynamicsReducer
}

private fun BackgroundDynamicsReduction.traceTags(
    includeBackground: Boolean = elapsedMillis > 0L,
): Set<String> = buildSet {
    if (includeBackground) add(TraceTag.BackgroundDrift)
    if (shockDecayed) add(TraceTag.ShockStateDecayed)
    if (omegaChanged) add(TraceTag.OmegaAccumulated)
}
