package io.openeden.transcript

data class HistoryCursor(
    val incarnationId: String,
    val completedAtMs: Long,
    val turnId: String,
)
