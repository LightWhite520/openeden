package io.openeden.memory

data class MemoryUtilityFilterConfig(
    val minSemanticSimilarity: Float = 0.05f,
    val minEmotionalSimilarity: Float = 0.05f,
    val entropyTolerance: Float = 0.5f,
    val baselineWindow: Int = 32,
) {
    init {
        require(minSemanticSimilarity.isFinite() && minSemanticSimilarity in -1.0f..1.0f)
        require(minEmotionalSimilarity.isFinite() && minEmotionalSimilarity in -1.0f..1.0f)
        require(entropyTolerance.isFinite() && entropyTolerance >= 0.0f)
        require(baselineWindow > 0)
    }
}
