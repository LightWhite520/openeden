package io.openeden.runtime.affect

import io.openeden.runtime.state.SHOCK_CONFIDENCE_GATE
import io.openeden.runtime.state.SHOCK_EMA_ALPHA

import io.openeden.bio.VectorDelta
import kotlin.math.exp
import kotlin.time.Clock
import kotlin.time.Instant

object ShockStateEngine {
    fun merge(current: ShockState?, signal: ShockSignal): ShockMergeResult {
        val currentIntensity = current?.intensity?.coerceIn(0.0f, 1.0f) ?: 0.0f
        val nextIntensity = (
            currentIntensity * (1.0f - SHOCK_EMA_ALPHA) +
                signal.intensity.coerceIn(0.0f, 1.0f) * SHOCK_EMA_ALPHA
            ).coerceIn(0.0f, 1.0f)
        val active = nextIntensity >= 0.05f
        val activated = current?.active != true && active
        return ShockMergeResult(
            state = ShockState(
                active = active,
                intensity = nextIntensity,
                description = signal.description,
                triggeredAt = if (current?.active == true) current.triggeredAt else signal.triggeredAt,
                decayLambda = signal.decayLambda,
                shockHeartbeatFired = if (activated) false else current?.shockHeartbeatFired ?: false,
            ),
            activated = activated,
        )
    }

    fun update(
        current: ShockState?,
        signal: Float,
        description: String,
        decayLambda: Float,
        now: Instant = Clock.System.now(),
    ): ShockState = merge(
        current = current,
        signal = ShockSignal(
            description = description,
            intensity = signal,
            decayLambda = decayLambda,
            triggeredAt = now,
        ),
    ).state

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
    ): ShockSignal? {
        if (emotionConfidence < SHOCK_CONFIDENCE_GATE) return null
        if (vectorDelta.p >= -0.4f || vectorDelta.f <= 0.3f) return null
        return ShockSignal(
            description = internalLogic.take(100),
            intensity = 1.0f,
            decayLambda = 0.001f,
            triggeredAt = now,
        )
    }
}
