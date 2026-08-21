package io.openeden.client

import kotlinx.serialization.Serializable

@Serializable
data class ChatRequest(
    val userId: String,
    val text: String,
)
