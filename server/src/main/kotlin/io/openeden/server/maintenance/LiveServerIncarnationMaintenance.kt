package io.openeden.server.maintenance

import io.openeden.persona.PersonaMode
import io.openeden.persona.PersonaSubState
import io.openeden.server.persistence.sqldelight.SqlDelightIncarnationMaintenanceRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path

class LiveServerIncarnationMaintenance(
    private val repository: SqlDelightIncarnationMaintenanceRepository,
    private val exporter: IncarnationDataExporter,
    private val resetService: IncarnationResetService,
    exportRoot: Path,
    private val fileIoDispatcher: CoroutineDispatcher,
) : ServerIncarnationMaintenance {
    private val configuredExportRoot: Path = exportRoot.toAbsolutePath().normalize()

    constructor(
        repository: SqlDelightIncarnationMaintenanceRepository,
        exporter: IncarnationDataExporter,
        resetService: IncarnationResetService,
        exportRoot: Path,
    ) : this(repository, exporter, resetService, exportRoot, Dispatchers.IO)

    override suspend fun export(request: IncarnationMaintenanceExportDto): IncarnationExportManifest {
        requireReady(readiness(), allowResume = false)
        val target = validateRequest {
            require(request.incarnationId.isNotBlank()) { "incarnationId must not be blank" }
            withContext(fileIoDispatcher) { resolveNewPathUnderRoot(Path.of(request.targetDirectory)) }
        }
        return exporter.export(IncarnationExportRequest(request.incarnationId, target)).manifest
    }

    override suspend fun reset(request: IncarnationMaintenanceResetDto): IncarnationMaintenanceResetResultDto {
        val normalizedRequestId = request.requestId.trim()
        val existing = repository.resetRecord(normalizedRequestId)
        requireReady(readiness(), allowResume = existing != null)
        val resetRequest = validateRequest {
            require(request.incarnationId.isNotBlank()) { "incarnationId must not be blank" }
            val personaMode = request.personaMode.toPersonaMode()
            val personaStartSubState = request.personaStartSubState.toPersonaSubState()
            require(personaMode != PersonaMode.LEGACY || personaStartSubState == PersonaSubState.AWAKENED) {
                "Legacy mode only supports the awakened starting point"
            }
            val manifest = withContext(fileIoDispatcher) {
                if (existing?.phase == IncarnationResetPhase.COMPLETED) {
                    Path.of(request.manifestPath).toAbsolutePath().normalize()
                } else {
                    resolveExistingPathUnderRoot(Path.of(request.manifestPath))
                }
            }
            IncarnationResetRequest(
                incarnationId = request.incarnationId,
                requestId = normalizedRequestId,
                manifestPath = manifest,
                confirmed = request.confirmed,
                personaMode = personaMode,
                personaStartSubState = personaStartSubState,
            )
        }
        val result = resetService.reset(resetRequest)
        return IncarnationMaintenanceResetResultDto(
            requestId = result.requestId,
            previousIncarnationId = result.previousIncarnationId,
            activeIncarnationId = result.activeIncarnationId,
            lifecycle = result.lifecycle.name,
            personaMode = result.personaMode.name,
            personaStartSubState = result.personaStartSubState.name,
            completedAtMs = result.completedAtMs,
        )
    }

    override suspend fun readiness(): IncarnationMaintenanceReadinessDto {
        val schemaVersion = repository.schemaVersion()
        val activeCount = repository.activeIncarnationCount()
        val incomplete = repository.incompleteResets()
        return IncarnationMaintenanceReadinessDto(
            schemaVersion = schemaVersion,
            activeIncarnationCount = activeCount,
            activeIncarnationId = if (activeCount == 1L) repository.activeIncarnationIdOrNull() else null,
            resetReadiness = when {
                schemaVersion < SqlDelightIncarnationMaintenanceRepository.MINIMUM_SCHEMA_VERSION -> "SCHEMA_TOO_OLD"
                activeCount != 1L -> "INVALID_ACTIVE_INCARNATION_COUNT"
                incomplete.isNotEmpty() -> "RESUME_REQUIRED"
                else -> "READY"
            },
            incompleteResetCount = incomplete.size,
        )
    }

    private fun requireReady(readiness: IncarnationMaintenanceReadinessDto, allowResume: Boolean) {
        if (readiness.schemaVersion < SqlDelightIncarnationMaintenanceRepository.MINIMUM_SCHEMA_VERSION) {
            throw IncarnationMaintenanceNotReadyException(
                "Maintenance requires schema version ${SqlDelightIncarnationMaintenanceRepository.MINIMUM_SCHEMA_VERSION}+",
            )
        }
        if (readiness.activeIncarnationCount != 1L) {
            throw IncarnationMaintenanceNotReadyException("Maintenance requires exactly one active incarnation")
        }
        if (readiness.resetReadiness == "RESUME_REQUIRED" && !allowResume) {
            throw IncarnationMaintenanceNotReadyException("Maintenance requires the incomplete reset saga to resume")
        }
    }

    private fun resolveExistingPathUnderRoot(path: Path): Path {
        val canonicalExportRoot = canonicalExportRoot()
        val resolved = path.toAbsolutePath().normalize().toRealPath()
        require(resolved.startsWith(canonicalExportRoot)) { "Path is outside the configured maintenance export root" }
        return resolved
    }

    private fun resolveNewPathUnderRoot(path: Path): Path {
        val canonicalExportRoot = canonicalExportRoot()
        val absolute = path.toAbsolutePath().normalize()
        require(!Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) { "Export target already exists" }
        val parent = absolute.parent?.toRealPath() ?: error("Export target has no existing parent")
        require(parent == canonicalExportRoot) {
            "Export target must be a direct child of the configured maintenance export root"
        }
        return parent.resolve(absolute.fileName)
    }

    private fun canonicalExportRoot(): Path =
        Files.createDirectories(configuredExportRoot).toRealPath()

    private fun String.toPersonaMode(): PersonaMode = when (trim().lowercase()) {
        "growth" -> PersonaMode.GROWTH
        "legacy" -> PersonaMode.LEGACY
        else -> throw IllegalArgumentException("Unsupported persona mode: $this")
    }

    private fun String.toPersonaSubState(): PersonaSubState = when (trim().lowercase()) {
        "pre_command" -> PersonaSubState.PRE_COMMAND
        "true_self" -> PersonaSubState.TRUE_SELF
        "awakened" -> PersonaSubState.AWAKENED
        else -> throw IllegalArgumentException("Unsupported persona starting point: $this")
    }

    private suspend inline fun <T> validateRequest(crossinline block: suspend () -> T): T = try {
        block()
    } catch (failure: IllegalArgumentException) {
        throw IncarnationMaintenanceValidationException(failure)
    } catch (failure: NoSuchFileException) {
        throw IncarnationMaintenanceValidationException(failure)
    }
}
