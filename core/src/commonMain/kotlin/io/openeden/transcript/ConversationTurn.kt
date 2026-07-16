package io.openeden.transcript

data class ConversationTurn(
    val turnId: String,
    val incarnationId: String,
    val sessionId: String,
    val platform: String,
    val scopeId: String,
    val userId: String,
    val userText: String,
    val assistantText: String,
    val completedAtMs: Long,
)
