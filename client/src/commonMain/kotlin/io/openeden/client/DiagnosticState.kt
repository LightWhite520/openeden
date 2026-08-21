package io.openeden.client

import kotlinx.serialization.Serializable

@Serializable
data class DiagnosticState(
    val sessionId: String,
    val vector: List<Float>,
    val omega: Float,
    val shockActive: Boolean,
    val shockIntensity: Float?,
    val evolutionIndex: Long,
    val derivedDissonance: Float,
)
