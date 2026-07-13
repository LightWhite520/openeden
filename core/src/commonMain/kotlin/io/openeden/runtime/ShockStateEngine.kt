package io.openeden.runtime

import io.openeden.bio.VectorDelta
import kotlin.math.exp
import kotlin.time.Clock
import kotlin.time.Instant

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
