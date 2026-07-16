package io.openeden.server.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class ConversationHistoryPageDto(
    val turns: List<ConversationTurnDto>,
    val before: String?,
    val hasMore: Boolean,
)
