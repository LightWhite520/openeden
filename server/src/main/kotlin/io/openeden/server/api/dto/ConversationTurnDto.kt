package io.openeden.server.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class ConversationTurnDto(
    val turnId: String,
    val platform: String,
    val scopeId: String,
    val userId: String,
    val userText: String,
    val assistantText: String,
    val completedAtMs: Long,
)
