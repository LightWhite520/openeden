package io.openeden.memory

data class VectorSearchHit(
    val entry: MemoryEntry,
    val semanticSimilarity: Float,
    val emotionalSimilarity: Float,
)
