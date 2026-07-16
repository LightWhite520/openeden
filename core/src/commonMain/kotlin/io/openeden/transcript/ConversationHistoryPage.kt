package io.openeden.transcript

data class ConversationHistoryPage(
    val turns: List<ConversationTurn>,
    val before: HistoryCursor?,
    val hasMore: Boolean,
)
