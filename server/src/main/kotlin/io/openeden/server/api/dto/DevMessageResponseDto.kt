package io.openeden.server.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class DevMessageResponseDto(
    val sessionId: String,
    val retrievalMode: String,
    val traceTags: List<String>,
    val promptPreview: String,
    val response: String?,
    val updatedVector: List<Float>,
    val evolutionIndex: Long,
    val diaryOutcome: String,
    val validationErrors: List<String>,
)
