package io.openeden.client

import kotlinx.serialization.Serializable

@Serializable
data class ChatResponse(
    val requestId: String,
    val status: String,
    val response: String? = null,
    val error: String? = null,
)
