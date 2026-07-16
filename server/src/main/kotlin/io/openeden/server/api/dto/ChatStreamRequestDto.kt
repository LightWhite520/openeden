package io.openeden.server.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class ChatStreamRequestDto(
    val userId: String = "local",
    val text: String = "",
    val clientRequestId: String = "",
)
