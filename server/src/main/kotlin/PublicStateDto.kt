package io.openeden.server

import kotlinx.serialization.Serializable

@Serializable
data class PublicStateDto(
    val sessionId: String,
    val status: String,
    val omega: Float,
    val shockActive: Boolean,
)
