package io.openeden.client

import kotlinx.serialization.Serializable

@Serializable
data class ConversationHistoryPage(
    val turns: List<ConversationTurn>,
    val before: String?,
    val hasMore: Boolean,
)
