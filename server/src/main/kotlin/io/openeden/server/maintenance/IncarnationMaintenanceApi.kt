package io.openeden.server.maintenance

import kotlinx.serialization.Serializable

@Serializable
data class IncarnationMaintenanceErrorDto(
    val code: String,
    val message: String,
)

@Serializable
data class IncarnationMaintenanceExportDto(
    val incarnationId: String,
    val targetDirectory: String,
)

@Serializable
data class IncarnationMaintenanceResetDto(
    val incarnationId: String,
    val requestId: String,
    val manifestPath: String,
    val confirmed: Boolean,
    val personaMode: String,
    val personaStartSubState: String,
)

@Serializable
data class IncarnationMaintenanceResetResultDto(
    val requestId: String,
    val previousIncarnationId: String,
    val activeIncarnationId: String,
    val lifecycle: String,
    val personaMode: String,
    val personaStartSubState: String,
    val completedAtMs: Long,
)

@Serializable
data class IncarnationMaintenanceReadinessDto(
    val schemaVersion: Int,
    val activeIncarnationCount: Long,
    val activeIncarnationId: String?,
    val resetReadiness: String,
    val incompleteResetCount: Int,
)

interface ServerIncarnationMaintenance {
    suspend fun export(request: IncarnationMaintenanceExportDto): IncarnationExportManifest
    suspend fun reset(request: IncarnationMaintenanceResetDto): IncarnationMaintenanceResetResultDto
    suspend fun readiness(): IncarnationMaintenanceReadinessDto
}

class IncarnationMaintenanceNotReadyException(message: String) : IllegalStateException(message)
