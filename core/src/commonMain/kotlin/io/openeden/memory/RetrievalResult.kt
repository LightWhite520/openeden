package io.openeden.memory

data class RetrievalResult(
    val mode: RetrievalMode,
    val injectionLabel: String,
    val memories: List<MemorySnippet>,
    val recentMemories: List<MemorySnippet> = emptyList(),
    val traceTags: Set<String> = emptySet(),
    val congruentCount: Int = 0,
    val positiveSkewCount: Int = 0,
    val filterAcceptedCount: Int = 0,
    val filterRejectedCount: Int = 0,
    val filterDegraded: Boolean = false,
    val lineageExcludedCount: Int = 0,
    val fingerprintExcludedCount: Int = 0,
    val backfillDepth: Int = 0,
    val underfilled: Boolean = false,
)
