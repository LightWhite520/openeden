package io.openeden.runtime

/** Durable progress marker advanced only after a narrative memory is persisted. */
data class DiaryCheckpoint(
    val lastCoveredRawMemoryId: String? = null,
    val lastSuccessfulDiaryAtMs: Long? = null,
    val lastNarrativeMemoryId: String? = null,
)

interface DiaryCheckpointStore {
    suspend fun read(sessionId: String): DiaryCheckpoint?
    suspend fun sessions(): Set<String>
}

data class DiaryRawMemoryCursor(
    val id: String,
    val createdAtMs: Long,
)

interface DiaryRawMemorySource {
    suspend fun sessionsWithRawMemories(): Set<String>
    suspend fun latestRawMemory(sessionId: String): DiaryRawMemoryCursor?
}
