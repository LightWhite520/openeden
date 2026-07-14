package io.openeden.server.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class ChatResponseDto(
    val requestId: String,
    val status: String,
    val response: String? = null,
    val error: String? = null,
)
