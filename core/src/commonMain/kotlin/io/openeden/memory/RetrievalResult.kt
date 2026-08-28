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
    val diagnostics: RetrievalDiagnostics = RetrievalDiagnostics(),
    val backfillDepth: Int = 0,
) {
    val lineageExcludedCount: Int
        get() = diagnostics.excludedByTurnLineage + diagnostics.excludedByMemoryLineage

    val fingerprintExcludedCount: Int
        get() = diagnostics.excludedByFingerprint

    val underfilled: Boolean
        get() = diagnostics.underfilled
}
