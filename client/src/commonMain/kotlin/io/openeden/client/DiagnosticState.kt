package io.openeden.client

import kotlinx.serialization.Serializable
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonIgnoreUnknownKeys

@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonIgnoreUnknownKeys
data class DiagnosticState(
    val sessionId: String,
    val vector: List<Float>,
    val omega: Float,
    val shockActive: Boolean,
    val shockIntensity: Float?,
    val evolutionIndex: Long,
    val derivedDissonance: Float,
)
