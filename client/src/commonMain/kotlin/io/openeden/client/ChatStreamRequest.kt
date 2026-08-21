package io.openeden.client

import kotlinx.serialization.Serializable

@Serializable
data class ChatStreamRequest(
    val userId: String,
    val text: String,
    val clientRequestId: String,
)
