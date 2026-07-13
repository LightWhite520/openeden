package io.openeden.server

import kotlinx.serialization.Serializable

@Serializable
data class ChatRequestDto(
    val userId: String = "local",
    val text: String = "",
)
