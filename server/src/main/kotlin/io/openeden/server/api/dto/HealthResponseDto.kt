package io.openeden.server.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class HealthResponseDto(
    val status: String,
    val service: String,
)
