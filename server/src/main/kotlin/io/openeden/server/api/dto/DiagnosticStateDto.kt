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
    val vectorDatabase: VectorDatabaseStatusDto? = null,
)

@Serializable
data class VectorDatabaseStatusDto(
    val backend: String,
    val collection: String?,
    val circuit: String,
    val fallbackActive: Boolean,
    val pendingProjectionCount: Long,
    val totalNonReadyProjectionCount: Long,
    val lastSuccessAtMs: Long?,
    val lastErrorCategory: String?,
    val lastErrorAtMs: Long?,
)
