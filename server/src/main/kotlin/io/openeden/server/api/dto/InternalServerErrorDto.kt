package io.openeden.server.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class InternalServerErrorDto(
    val code: String = "INTERNAL_SERVER_ERROR",
    val message: String = "An unexpected server error occurred",
    val traceId: String,
)
