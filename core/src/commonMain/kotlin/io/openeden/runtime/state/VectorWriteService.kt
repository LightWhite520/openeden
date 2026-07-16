package io.openeden.runtime.state

import io.openeden.runtime.affect.ShockState
import io.openeden.runtime.affect.ShockStateEngine
import io.openeden.runtime.session.SessionMutexRegistry
import io.openeden.runtime.session.SessionState
import io.openeden.runtime.session.SessionStateStore
import io.openeden.transcript.AtomicTurnCommitStore
import io.openeden.transcript.ConversationTurn

import io.openeden.bio.BioVector
import io.openeden.bio.VectorDelta
import io.openeden.trace.TraceTag
import kotlinx.coroutines.sync.withLock

class VectorWriteService(
    private val store: SessionStateStore,
    val mutexRegistry: SessionMutexRegistry = SessionMutexRegistry(),
) {
    internal fun isBackedBy(candidate: SessionStateStore): Boolean = store === candidate

    suspend fun applyLlmDelta(
        sessionId: String,
        preTickedSnapshot: BioVector,
        delta: VectorDelta,
    ): VectorWriteResult {
        val mutex = mutexRegistry.forSession(sessionId)
        return mutex.withLock { applyLlmDeltaLocked(sessionId, preTickedSnapshot, delta) }
    }

    suspend fun applyLlmDeltaLocked(
        sessionId: String,
        preTickedSnapshot: BioVector,
        delta: VectorDelta,
    ): VectorWriteResult {
        val latest = store.read(sessionId)
        val relativePreTickDelta = latest.vector.deltaTo(preTickedSnapshot)
        val updatedVector = latest.vector.apply(relativePreTickDelta).apply(delta)
        val updated = latest.copy(
            vector = updatedVector,
            evolutionIndex = latest.evolutionIndex + 1,
        )
        store.write(updated)
        return VectorWriteResult(
            state = updated,
            traceTags = setOf(TraceTag.VectorWriteSerialized),
        )
    }

    suspend fun commitTurnLocked(
        sessionId: String,
        preTickedSnapshot: BioVector,
        delta: VectorDelta,
        shock: ShockState?,
        lastUserActivityMs: Long?,
        turn: ConversationTurn? = null,
    ): VectorWriteResult {
        val latest = store.read(sessionId)
        val relativePreTickDelta = latest.vector.deltaTo(preTickedSnapshot)
        val updatedVector = latest.vector.apply(relativePreTickDelta).apply(delta)
        val updated = latest.copy(
            vector = updatedVector,
            evolutionIndex = latest.evolutionIndex + 1,
            shockState = shock ?: latest.shockState,
            omega = shock?.let { ShockStateEngine.omegaJump(latest.omega, it) } ?: latest.omega,
            lastUserActivityMs = lastUserActivityMs ?: latest.lastUserActivityMs,
        )
        val turnCommitOutcome = if (turn != null) {
            val atomicStore = store as? AtomicTurnCommitStore
                ?: error("Public turns require an atomic turn commit store")
            atomicStore.writeCommittedTurn(updated, turn)
        } else {
            store.write(updated)
            null
        }
        val committedState = if (turnCommitOutcome != null) store.read(sessionId) else updated
        return VectorWriteResult(
            state = committedState,
            traceTags = buildSet {
                add(TraceTag.VectorWriteSerialized)
                if (shock != null) add(TraceTag.ShockStateTransition)
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
        val updated = transform(store.read(sessionId))
        store.write(updated)
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

    suspend fun applyShock(sessionId: String, signal: ShockState): VectorWriteResult {
        val mutex = mutexRegistry.forSession(sessionId)
        return mutex.withLock { applyShockLocked(sessionId, signal) }
    }

    suspend fun applyShockLocked(sessionId: String, signal: ShockState): VectorWriteResult {
        val latest = store.read(sessionId)
        val updated = latest.copy(
            shockState = signal,
            omega = ShockStateEngine.omegaJump(latest.omega, signal),
        )
        store.write(updated)
        return VectorWriteResult(
            state = updated,
            traceTags = setOf(TraceTag.ShockStateTransition),
        )
    }

    suspend fun applyBackgroundDrift(sessionId: String, delta: VectorDelta): VectorWriteResult {
        val mutex = mutexRegistry.forSession(sessionId)
        return mutex.withLock {
            val latest = store.read(sessionId)
            val updated = latest.copy(vector = latest.vector.apply(delta))
            store.write(updated)
            VectorWriteResult(
                state = updated,
                traceTags = setOf(TraceTag.BackgroundDrift),
            )
        }
    }

    suspend fun applyRuntimeTick(
        sessionId: String,
        transform: (SessionState) -> Pair<SessionState, Set<String>>,
    ): VectorWriteResult {
        val mutex = mutexRegistry.forSession(sessionId)
        return mutex.withLock {
            val latest = store.read(sessionId)
            val (updated, traceTags) = transform(latest)
            store.write(updated)
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
}
