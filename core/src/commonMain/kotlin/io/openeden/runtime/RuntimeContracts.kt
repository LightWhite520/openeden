package io.openeden.runtime

import io.openeden.bio.BioVector
import io.openeden.bio.VectorDelta
import io.openeden.trace.TraceTag
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.exp
import kotlin.time.Clock
import kotlin.time.Instant

const val MAX_PRETICK_DELTA = 0.25f
const val PRETICK_SKIP_CONFIDENCE = 0.5f
const val SHOCK_CONFIDENCE_GATE = 0.65f
const val SHOCK_EMA_ALPHA = 0.4f

data class EmotionSignal(
    val delta: VectorDelta,
    val confidence: Float,
)

data class PreTickResult(
    val original: BioVector,
    val preTicked: BioVector,
    val appliedDelta: VectorDelta,
    val skipped: Boolean,
)

object PreTickEngine {
    fun apply(original: BioVector, signal: EmotionSignal): PreTickResult {
        if (signal.confidence < PRETICK_SKIP_CONFIDENCE) {
            return PreTickResult(
                original = original,
                preTicked = original,
                appliedDelta = VectorDelta.Zero,
                skipped = true,
            )
        }
        val scaled = signal.delta
            .scale(signal.confidence.coerceIn(0.0f, 1.0f))
            .clampMagnitude(MAX_PRETICK_DELTA)
        return PreTickResult(
            original = original,
            preTicked = original.apply(scaled),
            appliedDelta = scaled,
            skipped = false,
        )
    }
}

data class OmegaState(val value: Float) {
    init {
        require(value in 0.0f..1.0f)
    }

    fun increase(amount: Float): OmegaState = OmegaState((value + amount).coerceIn(value, 1.0f))
}

data class ShockState(
    val active: Boolean,
    val intensity: Float,
    val description: String,
    val triggeredAt: Instant,
    val decayLambda: Float,
    val shockHeartbeatFired: Boolean = false,
)

object ShockStateEngine {
    fun update(
        current: ShockState?,
        signal: Float,
        description: String,
        decayLambda: Float,
        now: Instant = Clock.System.now(),
    ): ShockState {
        val currentIntensity = current?.intensity ?: 0.0f
        val nextIntensity = currentIntensity * (1.0f - SHOCK_EMA_ALPHA) + signal * SHOCK_EMA_ALPHA
        return ShockState(
            active = nextIntensity >= 0.05f,
            intensity = nextIntensity.coerceIn(0.0f, 1.0f),
            description = description,
            triggeredAt = current?.triggeredAt ?: now,
            decayLambda = decayLambda,
            shockHeartbeatFired = current?.shockHeartbeatFired ?: false,
        )
    }

    fun decay(current: ShockState, elapsedMillis: Long): ShockState {
        val elapsedSeconds = elapsedMillis.coerceAtLeast(0).toDouble() / 1000.0
        val decayed = current.intensity * exp(-current.decayLambda * elapsedSeconds).toFloat()
        return current.copy(
            active = decayed >= 0.05f,
            intensity = decayed.coerceIn(0.0f, 1.0f),
        )
    }

    fun omegaJump(omega: OmegaState, shock: ShockState): OmegaState =
        omega.increase(shock.intensity * 0.15f)

    fun detectFromLlmOutput(
        vectorDelta: VectorDelta,
        emotionConfidence: Float,
        internalLogic: String,
        now: Instant = Clock.System.now(),
    ): ShockState? {
        if (emotionConfidence < SHOCK_CONFIDENCE_GATE) return null
        if (vectorDelta.p >= -0.4f || vectorDelta.f <= 0.3f) return null
        return update(
            current = null,
            signal = 1.0f,
            description = internalLogic.take(100),
            decayLambda = 0.001f,
            now = now,
        )
    }
}

data class SessionState(
    val sessionId: String,
    val vector: BioVector,
    val origin: BioVector,
    val omega: OmegaState,
    val shockState: ShockState?,
    val evolutionIndex: Long,
    // Epoch-millis of the last USER-initiated turn. Drives the heartbeat silence gates (§9.3).
    // Null = no user turn observed yet. Heartbeat turns MUST NOT update it.
    val lastUserActivityMs: Long? = null,
)

interface SessionStateStore {
    suspend fun read(sessionId: String): SessionState

    /** Return the existing session, or a fresh neutral one if absent. Lets the pipeline depend on
     *  this interface rather than a concrete store. Implementations need not persist on read. */
    suspend fun readOrCreate(sessionId: String): SessionState = read(sessionId)

    suspend fun write(state: SessionState)

    /** All session ids the store currently knows about — the heartbeat scheduler enumerates these. */
    suspend fun sessionIds(): Set<String>

    companion object {
        fun neutral(sessionId: String): SessionState = SessionState(
            sessionId = sessionId,
            vector = BioVector.Neutral,
            origin = BioVector.Neutral,
            omega = OmegaState(0.0f),
            shockState = null,
            evolutionIndex = 0,
            lastUserActivityMs = null,
        )
    }
}

class SessionMutexRegistry {
    private val registryMutex = Mutex()
    private val mutexes = mutableMapOf<String, Mutex>()

    suspend fun forSession(sessionId: String): Mutex = registryMutex.withLock {
        mutexes.getOrPut(sessionId) { Mutex() }
    }
}

class SessionTurnGate(
    private val registry: SessionMutexRegistry,
) {
    suspend fun <T> withSession(sessionId: String, block: suspend () -> T): T =
        registry.forSession(sessionId).withLock { block() }
}

class VectorWriteService(
    private val store: SessionStateStore,
    val mutexRegistry: SessionMutexRegistry = SessionMutexRegistry(),
) {
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

data class VectorWriteResult(
    val state: SessionState,
    val traceTags: Set<String>,
)
