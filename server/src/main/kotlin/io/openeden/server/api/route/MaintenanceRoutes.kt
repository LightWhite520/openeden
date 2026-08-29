package io.openeden.server.api.route

import io.openeden.server.maintenance.IncarnationMaintenanceExportDto
import io.openeden.server.maintenance.IncarnationMaintenanceResetDto
import io.openeden.server.maintenance.ServerIncarnationMaintenance
import io.openeden.server.maintenance.IncarnationMaintenanceErrorDto
import io.openeden.server.maintenance.IncarnationMaintenanceNotReadyException
import io.openeden.server.maintenance.IncarnationMaintenanceValidationException
import io.openeden.server.maintenance.IncarnationResetRejectedException
import io.openeden.server.maintenance.IncarnationResetRejection
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CancellationException

val ServerIncarnationMaintenanceKey =
    AttributeKey<ServerIncarnationMaintenance>("openeden.server-incarnation-maintenance")

internal fun Route.installMaintenanceRoutes(
    access: MaintenanceAccess,
    maintenance: ServerIncarnationMaintenance?,
) {
    get("/api/v1/maintenance/incarnation/readiness") {
        if (!call.authorizeMaintenance(access)) return@get
        call.respondMaintenance { checkNotNull(maintenance) { "Server maintenance service is not configured" }.readiness() }
    }
    post("/api/v1/maintenance/incarnation/export") {
        if (!call.authorizeMaintenance(access)) return@post
        call.respondMaintenance { checkNotNull(maintenance).export(call.receive<IncarnationMaintenanceExportDto>()) }
    }
    post("/api/v1/maintenance/incarnation/reset") {
        if (!call.authorizeMaintenance(access)) return@post
        call.respondMaintenance { checkNotNull(maintenance).reset(call.receive<IncarnationMaintenanceResetDto>()) }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondMaintenance(
    block: suspend () -> Any,
) {
    try {
        respond(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (rejected: IncarnationResetRejectedException) {
        val status = when (rejected.reason) {
            IncarnationResetRejection.BLANK_REQUEST_ID,
            IncarnationResetRejection.CONFIRMATION_REQUIRED,
            IncarnationResetRejection.EXPORT_INCOMPLETE,
            IncarnationResetRejection.EXPORT_HASH_INVALID,
            IncarnationResetRejection.EXPORT_INCARNATION_MISMATCH -> HttpStatusCode.BadRequest
            else -> HttpStatusCode.Conflict
        }
        respond(status, IncarnationMaintenanceErrorDto(rejected.reason.name, "Maintenance request was rejected"))
    } catch (_: BadRequestException) {
        respond(HttpStatusCode.BadRequest, IncarnationMaintenanceErrorDto("INVALID_REQUEST", "Request body is invalid"))
    } catch (_: IncarnationMaintenanceValidationException) {
        respond(HttpStatusCode.BadRequest, IncarnationMaintenanceErrorDto("INVALID_REQUEST", "Request validation failed"))
    } catch (_: IncarnationMaintenanceNotReadyException) {
        respond(HttpStatusCode.Conflict, IncarnationMaintenanceErrorDto("MAINTENANCE_NOT_READY", "Maintenance is not ready"))
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.authorizeMaintenance(
    access: MaintenanceAccess,
): Boolean {
    if (!access.enabled) {
        respond(HttpStatusCode.NotFound)
        return false
    }
    val token = request.headers[HttpHeaders.Authorization]
        ?.removePrefix("Bearer ")
        ?.takeIf(String::isNotBlank)
    if (!access.authorizes(token)) {
        respond(HttpStatusCode.Unauthorized)
        return false
    }
    return true
}
