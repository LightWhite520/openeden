package io.openeden.memory

data class VectorSearchRequest(
    val sessionId: String,
    val semanticEmbedding: List<Float>,
    val emotionalEmbedding: List<Float>? = null,
    val room: MemoryRoom? = null,
    val kind: MemoryKind? = null,
    val limit: Int = 6,
)
