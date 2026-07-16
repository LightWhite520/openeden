package io.openeden.server.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class DiagnosticStateDto(
    val sessionId: String,
    val vector: List<Float>,
    val omega: Float,
    val shockActive: Boolean,
    val shockIntensity: Float?,
    val evolutionIndex: Long,
    val derivedDissonance: Float,
)
