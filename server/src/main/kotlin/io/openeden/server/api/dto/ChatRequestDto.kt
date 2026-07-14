package io.openeden.server.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class ChatRequestDto(
    val userId: String = "local",
    val text: String = "",
)
