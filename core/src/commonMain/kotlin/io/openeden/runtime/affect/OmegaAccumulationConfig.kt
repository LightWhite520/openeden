package io.openeden.runtime.affect

data class OmegaAccumulationConfig(
    val highThreshold: Float = 0.75f,
    val sWearRate: Float = 0.00005f,
    val dissonanceWearRate: Float = 0.00005f,
    val fearEntropyMultiplier: Float = 1.5f,
) {
    init {
        require(highThreshold in 0.0f..1.0f) { "omega high threshold must be in [0, 1]" }
        require(sWearRate >= 0.0f) { "sWearRate must be non-negative" }
        require(dissonanceWearRate >= 0.0f) { "dissonanceWearRate must be non-negative" }
        require(fearEntropyMultiplier >= 1.0f) { "fearEntropyMultiplier must be at least 1" }
    }
}
