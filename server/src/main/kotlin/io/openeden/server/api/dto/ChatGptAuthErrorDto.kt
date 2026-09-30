package io.openeden.server.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class ChatGptAuthErrorDto(val code: String, val message: String, val retryable: Boolean, val traceId: String)
