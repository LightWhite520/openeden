package io.openeden.memory

import io.openeden.bio.BioVector

interface MemoryStore : MemoryRetriever {
    suspend fun write(entry: MemoryEntry): Set<String>
    suspend fun stableVectors(sessionId: String, limit: Int): List<BioVector>
    suspend fun recent(sessionId: String, limit: Int): List<MemorySnippet> = emptyList()
}
