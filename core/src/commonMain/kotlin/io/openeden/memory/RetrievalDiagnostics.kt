package io.openeden.memory

data class RetrievalDiagnostics(
    val excludedByTurnLineage: Int = 0,
    val excludedByMemoryLineage: Int = 0,
    val excludedByFingerprint: Int = 0,
    val backfilled: Int = 0,
    val underfilled: Boolean = false,
)
