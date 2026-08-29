package io.openeden.runtime.state

import io.openeden.bio.BioVector
import io.openeden.runtime.affect.OmegaAccumulationConfig
import io.openeden.runtime.affect.OmegaAccumulationEngine
import io.openeden.runtime.affect.OmegaState
import io.openeden.runtime.affect.ShockState
import io.openeden.runtime.affect.ShockStateEngine
import io.openeden.runtime.tick.SineWaveDimension
import io.openeden.runtime.tick.SineWaveFluctuationEngine
import io.openeden.runtime.tick.SineWaveFluctuationProfile

class BackgroundDynamicsReducer(
    private val fluctuation: SineWaveFluctuationEngine,
    private val omegaConfig: OmegaAccumulationConfig,
    private val startedAtMs: Long,
) {
    fun reduce(
        vector: BioVector,
        shockState: ShockState?,
        omega: OmegaState,
        previousAtMs: Long?,
        throughAtMs: Long?,
    ): BackgroundDynamicsReduction {
        if (throughAtMs == null) return unchanged(vector, shockState, omega, previousAtMs)
        if (previousAtMs == null) {
            return unchanged(vector, shockState, omega, throughAtMs, baseline = true)
        }

        val consumedAtMs = maxOf(previousAtMs, throughAtMs)
        val elapsedMillis = consumedAtMs - previousAtMs
        val previousElapsed = (previousAtMs - startedAtMs).coerceAtLeast(0L)
        val currentElapsed = (consumedAtMs - startedAtMs).coerceAtLeast(previousElapsed)
        val driftedVector = vector.apply(fluctuation.deltaBetween(previousElapsed, currentElapsed))
        val decayedShock = shockState?.let { ShockStateEngine.decay(it, elapsedMillis) }
        val accumulatedOmega = OmegaAccumulationEngine.accumulate(
            omega = omega,
            vector = driftedVector,
            elapsedMillis = elapsedMillis,
            config = omegaConfig,
        )
        return BackgroundDynamicsReduction(
            vector = driftedVector,
            shockState = decayedShock,
            omega = accumulatedOmega,
            consumedAtMs = consumedAtMs,
            elapsedMillis = elapsedMillis,
            baseline = false,
            shockDecayed = decayedShock != shockState,
            omegaChanged = accumulatedOmega != omega,
        )
    }

    private fun unchanged(
        vector: BioVector,
        shockState: ShockState?,
        omega: OmegaState,
        consumedAtMs: Long?,
        baseline: Boolean = false,
    ): BackgroundDynamicsReduction = BackgroundDynamicsReduction(
        vector = vector,
        shockState = shockState,
        omega = omega,
        consumedAtMs = consumedAtMs,
        elapsedMillis = 0L,
        baseline = baseline,
        shockDecayed = false,
        omegaChanged = false,
    )

    companion object {
        fun stationary(
            omegaConfig: OmegaAccumulationConfig = OmegaAccumulationConfig(),
            startedAtMs: Long = 0L,
        ): BackgroundDynamicsReducer = BackgroundDynamicsReducer(
            fluctuation = SineWaveFluctuationEngine(
                SineWaveFluctuationProfile(
                    dimensions = List(8) {
                        SineWaveDimension(amplitude = 0.0f, frequencyHz = 0.0f, phaseRadians = 0.0f)
                    },
                ),
            ),
            omegaConfig = omegaConfig,
            startedAtMs = startedAtMs,
        )
    }
}
