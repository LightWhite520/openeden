package io.openeden.memory

data class RetrievalResult(
    val mode: RetrievalMode,
    val injectionLabel: String,
    val memories: List<MemorySnippet>,
    val recentMemories: List<MemorySnippet> = emptyList(),
    val traceTags: Set<String> = emptySet(),
)
