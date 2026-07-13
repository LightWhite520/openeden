package io.openeden.codebook

data class QuantizationResult(
    val activeNodes: List<String>,
    val semanticDefinitions: List<String>,
    val confidence: Float,
    val traceTags: Set<String> = emptySet(),
)
