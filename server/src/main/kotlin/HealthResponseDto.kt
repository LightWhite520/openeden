package io.openeden.server

import kotlinx.serialization.Serializable

@Serializable
data class HealthResponseDto(
    val status: String,
    val service: String,
)
