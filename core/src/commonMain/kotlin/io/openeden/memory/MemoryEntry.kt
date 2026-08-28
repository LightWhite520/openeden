package io.openeden.memory

import kotlinx.serialization.Serializable

@Serializable
data class MemoryEntry(
    val id: String,
    val sessionId: String,
    val content: String,
    val room: MemoryRoom,
    val kind: MemoryKind,
    val tags: Set<String> = emptySet(),
    val semanticEmbedding: List<Float>,
    val emotionalEmbedding: List<Float>,
    val metadata: MemoryMetadata,
    val createdAtMs: Long = 0L,
)
