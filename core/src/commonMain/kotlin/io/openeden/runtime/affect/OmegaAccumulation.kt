package io.openeden.runtime.affect

import io.openeden.bio.BioVector

object OmegaAccumulationEngine {
    fun accumulate(
        omega: OmegaState,
        vector: BioVector,
        elapsedMillis: Long,
        config: OmegaAccumulationConfig,
    ): OmegaState {
        val seconds = elapsedMillis.coerceAtLeast(0).toFloat() / 1000.0f
        val entropyWear = if (vector.s >= config.highThreshold) config.sWearRate * seconds else 0.0f
        val dissonance = vector.derivedDissonance()
        val dissonanceWear = if (dissonance >= config.highThreshold) {
            config.dissonanceWearRate * seconds
        } else {
            0.0f
        }
        val multiplier = if (vector.s >= config.highThreshold && vector.f >= config.highThreshold) {
            config.fearEntropyMultiplier
        } else {
            1.0f
        }
        return omega.increase((entropyWear + dissonanceWear) * multiplier)
    }
}
