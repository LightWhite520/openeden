package io.openeden.memory

data class VectorSearchHit(
    val memoryId: String,
    val entry: MemoryEntry?,
    val semanticSimilarity: Float,
    val emotionalSimilarity: Float,
)
