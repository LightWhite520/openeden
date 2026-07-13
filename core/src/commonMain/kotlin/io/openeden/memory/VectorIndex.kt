package io.openeden.memory

interface VectorIndex {
    suspend fun insert(entry: MemoryEntry)
    suspend fun remove(memoryId: String)
    suspend fun rebuild(entries: Iterable<MemoryEntry>, batchSize: Int = 256)
    suspend fun search(request: VectorSearchRequest): List<VectorSearchHit>
    suspend fun markDirty()
}
