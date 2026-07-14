package io.openeden.server.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class DevMessageRequestDto(
    val platform: String,
    val scopeId: String,
    val userId: String,
    val text: String,
    val emotionConfidence: Float,
    val deltaL: Float = 0.0f,
    val deltaP: Float = 0.0f,
    val deltaE: Float = 0.0f,
    val deltaS: Float = 0.0f,
    val deltaTau: Float = 0.0f,
    val deltaV: Float = 0.0f,
    val deltaM: Float = 0.0f,
    val deltaF: Float = 0.0f,
)
