package io.openeden.server.maintenance

import io.openeden.persona.PersonaMode
import io.openeden.persona.PersonaSubState
import io.openeden.runtime.incarnation.IncarnationTurnGate
import io.openeden.server.persistence.sqldelight.SqlDelightIncarnationMaintenanceRepository
import java.util.UUID

class IncarnationResetService(
    private val repository: SqlDelightIncarnationMaintenanceRepository,
    private val exporter: IncarnationDataExporter,
    private val mutationGate: IncarnationTurnGate,
    private val projectionEraser: IncarnationProjectionEraser,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val freshIncarnationId: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun reset(request: IncarnationResetRequest): IncarnationResetResult {
        validateRequest(request)
        val normalized = request.copy(requestId = request.requestId.trim())
        return mutationGate.withIncarnation(normalized.incarnationId) {
            val existing = repository.resetRecord(normalized.requestId)
            if (existing != null) validateReplayFields(existing, normalized)
            if (existing?.phase == IncarnationResetPhase.COMPLETED) {
                return@withIncarnation checkNotNull(repository.completedReset(normalized.requestId))
            }
            if (existing == null) {
                val activeIncarnationId = repository.activeIncarnationId()
                if (activeIncarnationId != normalized.incarnationId) {
                    reject(
                        IncarnationResetRejection.STALE_INCARNATION_ID,
                        "Requested incarnation is stale; active incarnation is $activeIncarnationId",
                    )
                }
            }
            val unverified = try {
                exporter.readUnverified(normalized.manifestPath)
            } catch (failure: IncarnationExportVerificationException) {
                throw mapVerificationFailure(failure)
            }
            if (unverified.status != IncarnationExportStatus.COMPLETED) {
                reject(IncarnationResetRejection.EXPORT_INCOMPLETE, "Export manifest is not completed")
            }
            if (unverified.incarnationId != normalized.incarnationId) {
                reject(
                    IncarnationResetRejection.EXPORT_INCARNATION_MISMATCH,
                    "Export manifest belongs to ${unverified.incarnationId}",
                )
            }
            val manifest = try {
                exporter.verify(normalized.manifestPath)
            } catch (failure: IncarnationExportVerificationException) {
                throw mapVerificationFailure(failure)
            }

            if (existing != null) validateReplay(existing, normalized, manifest)
            var saga = existing ?: repository.prepareReset(
                previousIncarnationId = normalized.incarnationId,
                requestId = normalized.requestId,
                manifestSha256 = manifest.manifestSha256,
                manifestPath = normalized.manifestPath.normalizedString(),
                expectedPayloadSha256 = manifest.payloadSha256,
                freshIncarnationId = freshIncarnationId().also { id -> require(id.isNotBlank()) },
                personaMode = normalized.personaMode,
                personaStartSubState = normalized.personaStartSubState,
                confirmed = normalized.confirmed,
                preparedAtMs = nowMs(),
            )
            if (saga.phase == IncarnationResetPhase.COMPLETED) {
                return@withIncarnation checkNotNull(repository.completedReset(normalized.requestId))
            }
            if (saga.phase == IncarnationResetPhase.PREPARED) {
                projectionEraser.eraseAndVerify(saga.previousIncarnationId, saga.projectionModelIds)
                saga = repository.markProjectionsVerified(normalized.requestId)
            }
            check(saga.phase == IncarnationResetPhase.PROJECTIONS_VERIFIED)
            repository.completeReset(normalized.requestId, nowMs())
        }
    }

    suspend fun resumeIncomplete() {
        repository.incompleteResets().forEach { reset ->
            reset(
                IncarnationResetRequest(
                    incarnationId = reset.previousIncarnationId,
                    requestId = reset.requestId,
                    manifestPath = java.nio.file.Path.of(reset.manifestPath),
                    confirmed = reset.confirmed,
                    personaMode = reset.personaMode,
                    personaStartSubState = reset.personaStartSubState,
                ),
            )
        }
    }

    private fun validateReplay(
        existing: PreparedIncarnationReset,
        request: IncarnationResetRequest,
        manifest: IncarnationExportManifest,
    ) {
        val identical = existing.previousIncarnationId == request.incarnationId &&
            existing.manifestSha256 == manifest.manifestSha256 &&
            existing.payloadSha256 == manifest.payloadSha256 &&
            existing.personaMode == request.personaMode &&
            existing.personaStartSubState == request.personaStartSubState &&
            existing.confirmed == request.confirmed
        if (!identical) {
            reject(
                IncarnationResetRejection.REQUEST_ID_CONFLICT,
                "requestId is already bound to a different reset request",
            )
        }
    }

    private fun validateReplayFields(
        existing: PreparedIncarnationReset,
        request: IncarnationResetRequest,
    ) {
        if (
            existing.previousIncarnationId != request.incarnationId ||
            existing.manifestPath != request.manifestPath.normalizedString() ||
            existing.personaMode != request.personaMode ||
            existing.personaStartSubState != request.personaStartSubState ||
            existing.confirmed != request.confirmed
        ) {
            reject(
                IncarnationResetRejection.REQUEST_ID_CONFLICT,
                "requestId is already bound to a different reset request",
            )
        }
    }

    private fun validateRequest(request: IncarnationResetRequest) {
        if (request.requestId.isBlank()) {
            reject(IncarnationResetRejection.BLANK_REQUEST_ID, "requestId must not be blank")
        }
        if (!request.confirmed) {
            reject(IncarnationResetRejection.CONFIRMATION_REQUIRED, "Explicit reset confirmation is required")
        }
        require(request.incarnationId.isNotBlank()) { "incarnationId must not be blank" }
        require(
            request.personaMode != PersonaMode.LEGACY ||
                request.personaStartSubState == PersonaSubState.AWAKENED,
        ) { "Legacy mode only supports the awakened starting point" }
    }

    private fun mapVerificationFailure(failure: IncarnationExportVerificationException) =
        IncarnationResetRejectedException(
            reason = when (failure.failure) {
                IncarnationExportVerificationFailure.INCOMPLETE -> IncarnationResetRejection.EXPORT_INCOMPLETE
                IncarnationExportVerificationFailure.INVALID_HASH -> IncarnationResetRejection.EXPORT_HASH_INVALID
            },
            message = failure.message ?: "Export verification failed",
            cause = failure,
        )

    private fun reject(reason: IncarnationResetRejection, message: String): Nothing =
        throw IncarnationResetRejectedException(reason, message)

    private fun java.nio.file.Path.normalizedString(): String = toAbsolutePath().normalize().toString()
}
