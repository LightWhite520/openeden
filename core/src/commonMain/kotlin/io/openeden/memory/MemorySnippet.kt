package io.openeden.memory

data class MemorySnippet(
    val id: String = "",
    val content: String,
    val metadata: MemoryMetadata,
    val score: Float = 0.0f,
)
