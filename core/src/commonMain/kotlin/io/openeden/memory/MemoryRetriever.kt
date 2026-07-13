package io.openeden.memory

interface MemoryRetriever {
    suspend fun retrieve(request: RetrievalRequest): RetrievalResult
}
