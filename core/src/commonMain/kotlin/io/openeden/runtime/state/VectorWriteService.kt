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
import io.openeden.runtime.session.SessionMutexRegistry
import io.openeden.runtime.session.SessionState
import io.openeden.runtime.session.SessionStateStore
import io.openeden.transcript.AtomicTurnCommitStore
import io.openeden.transcript.ConversationTurn
import io.openeden.transcript.TurnCommitOutcome

import io.openeden.bio.BioVector
import io.openeden.bio.VectorDelta
import io.openeden.trace.TraceTag
import kotlinx.coroutines.sync.withLock

class VectorWriteService(
    private val store: SessionStateStore? = null,
    val mutexRegistry: SessionMutexRegistry = SessionMutexRegistry(),
    private val incarnationStore: IncarnationStateStore? = null,
    val incarnationMutexRegistry: IncarnationMutexRegistry = IncarnationMutexRegistry(),
    private val inferenceExecutor: InferenceExecutor = DirectInferenceExecutor,
) {
    private val incarnationTurnGate = IncarnationTurnGate(incarnationMutexRegistry)

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
        turn: ConversationTurn? = null,
    ): VectorWriteResult<IncarnationState> = incarnationTurnGate.withIncarnation(incarnationId) {
        val latest = bioStore.read(incarnationId)
        val updatedVector = inferenceExecutor.run {
            // Rebase this turn's pre-tick effect onto the latest serialized Bio state.
            val preTickDelta = baseSnapshot.deltaTo(preTickedSnapshot)
            latest.vector.apply(preTickDelta).apply(delta)
        }
        val shockMerge = shockSignal?.let { signal ->
            inferenceExecutor.run { ShockStateEngine.merge(latest.shockState, signal) }
        }
        val updated = latest.copy(
            vector = updatedVector,
            origin = latest.origin,
            evolutionIndex = latest.evolutionIndex + 1,
            shockState = shockMerge?.state ?: latest.shockState,
            omega = if (shockMerge?.activated == true) {
                inferenceExecutor.run { latest.omega.increase(shockMerge.state.intensity * 0.15f) }
            } else {
                latest.omega
            },
            lastUserActivityMs = maxOfNullable(latest.lastUserActivityMs, lastUserActivityMs),
        )
        val outcome = if (turn == null) {
            bioStore.write(updated)
            null
        } else {
            bioStore.writeCommittedTurn(updated, turn)
        }
        val committed = if (outcome == TurnCommitOutcome.ALREADY_COMMITTED) bioStore.read(incarnationId) else updated
        VectorWriteResult(
            state = committed,
            traceTags = if (outcome == TurnCommitOutcome.ALREADY_COMMITTED) {
                setOf(TraceTag.TranscriptRetry)
            } else {
                buildSet {
                    add(TraceTag.VectorWriteSerialized)
                    if (shockSignal != null) add(TraceTag.ShockStateTransition)
                }
            },
            turnCommitOutcome = outcome,
        )
    }

    suspend fun readOrCreateIncarnation(
        incarnationId: String,
        personaMode: PersonaMode,
        personaStartSubState: PersonaSubState,
    ): IncarnationState = incarnationTurnGate.withIncarnation(incarnationId) {
        bioStore.readOrCreate(incarnationId, personaMode, personaStartSubState)
    }

    suspend fun updateIncarnation(
        incarnationId: String,
        transform: (IncarnationState) -> IncarnationState,
    ): IncarnationState = incarnationTurnGate.withIncarnation(incarnationId) {
        val updated = transform(bioStore.read(incarnationId))
        bioStore.write(updated)
        updated
    }

    private fun maxOfNullable(first: Long?, second: Long?): Long? = when {
        first == null -> second
        second == null -> first
        else -> maxOf(first, second)
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
    ): VectorWriteResult<IncarnationState> = incarnationTurnGate.withIncarnation(incarnationId) {
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
    ): VectorWriteResult<IncarnationState> = incarnationTurnGate.withIncarnation(incarnationId) {
        val latest = bioStore.read(incarnationId)
        val updated = inferenceExecutor.run { latest.copy(vector = latest.vector.apply(delta)) }
        bioStore.write(updated)
        VectorWriteResult(updated, setOf(TraceTag.BackgroundDrift))
    }

    suspend fun applyRuntimeTickForIncarnation(
        incarnationId: String,
        transform: suspend (IncarnationState) -> Pair<IncarnationState, Set<String>>,
    ): VectorWriteResult<IncarnationState> = incarnationTurnGate.withIncarnation(incarnationId) {
        val (updated, traceTags) = transform(bioStore.read(incarnationId))
        bioStore.write(updated)
        VectorWriteResult(updated, traceTags)
    }

    private val sessionStore: SessionStateStore
        get() = checkNotNull(store) { "Session-scoped vector writes are not configured" }

    private val bioStore: IncarnationStateStore
        get() = checkNotNull(incarnationStore) { "Incarnation-scoped vector writes are not configured" }
}
