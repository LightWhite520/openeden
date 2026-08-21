package io.openeden.client

import kotlinx.serialization.Serializable

@Serializable
data class ConversationTurn(
    val turnId: String,
    val platform: String,
    val scopeId: String,
    val userId: String,
    val userText: String,
    val assistantText: String,
    val completedAtMs: Long,
)
