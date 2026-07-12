package io.openeden.server

import kotlinx.serialization.Serializable

@Serializable
data class ChatRequestDto(
    val userId: String = "local",
    val text: String = "",
)

@Serializable
data class ChatResponseDto(
    val requestId: String,
    val status: String,
    val response: String? = null,
    val error: String? = null,
)

@Serializable
data class HealthResponseDto(
    val status: String,
    val service: String,
)

@Serializable
data class PublicStateDto(
    val sessionId: String,
    val status: String,
    val omega: Float,
    val shockActive: Boolean,
)
