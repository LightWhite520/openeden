package io.openeden.runtime.diary

import io.openeden.memory.MemorySnippet
import io.openeden.memory.MemoryEntry

data class DiaryRawSlice(
    val memories: List<MemorySnippet>,
    val upperBoundMemoryId: String,
)

interface DiaryDataSource {
    suspend fun uncoveredRawSlice(sessionId: String, throughMemoryId: String?, limit: Int): DiaryRawSlice?

    suspend fun loadSourceMemory(memoryId: String): MemoryEntry? = null
}

class CheckpointedDiaryDataSource(
    private val checkpoints: DiaryCheckpointStore,
    private val raw: DiaryRawMemorySource,
    private val rangeReader: suspend (String, String?, String?, Int) -> List<MemorySnippet>,
    private val sourceMemoryLoader: suspend (String) -> MemoryEntry?,
) : DiaryDataSource {
    constructor(
        checkpoints: DiaryCheckpointStore,
        raw: DiaryRawMemorySource,
        rangeReader: suspend (String, String?, String?, Int) -> List<MemorySnippet>,
    ) : this(checkpoints, raw, rangeReader, { null })

    override suspend fun uncoveredRawSlice(sessionId: String, throughMemoryId: String?, limit: Int): DiaryRawSlice? {
        val checkpoint = checkpoints.read(sessionId)
        val memories = rangeReader(sessionId, checkpoint?.lastCoveredRawMemoryId, throughMemoryId, limit)
        return memories.takeIf { it.isNotEmpty() }?.let { DiaryRawSlice(it, throughMemoryId ?: it.last().id) }
    }

    override suspend fun loadSourceMemory(memoryId: String): MemoryEntry? = sourceMemoryLoader(memoryId)
}
