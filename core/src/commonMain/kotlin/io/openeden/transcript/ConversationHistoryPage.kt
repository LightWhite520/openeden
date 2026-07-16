package io.openeden.transcript

data class HistoryCursor(
    val incarnationId: String,
    val completedAtMs: Long,
    val turnId: String,
)

data class ConversationHistoryPage(
    val turns: List<ConversationTurn>,
    val before: HistoryCursor?,
    val hasMore: Boolean,
)
