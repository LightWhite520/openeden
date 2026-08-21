package io.openeden.client

import kotlinx.serialization.Serializable

@Serializable
data class PublicState(
    val sessionId: String,
    val status: String,
    val omega: Float,
    val shockActive: Boolean,
)
