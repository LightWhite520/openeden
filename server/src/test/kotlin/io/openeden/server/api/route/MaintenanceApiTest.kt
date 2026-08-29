package io.openeden.server.api.route

import io.openeden.server.api.plugin.configureSerialization
import io.openeden.server.api.plugin.configureStatusPages
import io.openeden.server.maintenance.IncarnationExportManifest
import io.openeden.server.maintenance.IncarnationMaintenanceExportDto
import io.openeden.server.maintenance.IncarnationMaintenanceReadinessDto
import io.openeden.server.maintenance.IncarnationMaintenanceResetDto
import io.openeden.server.maintenance.IncarnationMaintenanceResetResultDto
import io.openeden.server.maintenance.ServerIncarnationMaintenance
import io.openeden.server.maintenance.IncarnationResetRejectedException
import io.openeden.server.maintenance.IncarnationResetRejection
import io.openeden.server.maintenance.IncarnationMaintenanceErrorDto
import io.openeden.server.maintenance.IncarnationMaintenanceValidationException
import io.openeden.server.api.dto.InternalServerErrorDto
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MaintenanceApiTest {
    @Test
    fun `maintenance route is hidden when disabled`() = testApplication {
        application {
            attributes.put(MaintenanceAccessKey, MaintenanceAccess.disabled())
            configureSerialization()
            configureWebsockets()
            configureStatusPages()
            configureRouting()
        }

        assertEquals(HttpStatusCode.NotFound, client.get(READINESS_PATH).status)
    }

    @Test
    fun `maintenance readiness requires bearer token and uses server owned service`() = testApplication {
        application {
            attributes.put(MaintenanceAccessKey, MaintenanceAccess.enabled("maintenance-secret"))
            attributes.put(ServerIncarnationMaintenanceKey, FakeMaintenance)
            configureSerialization()
            configureWebsockets()
            configureStatusPages()
            configureRouting()
        }

        assertEquals(HttpStatusCode.Unauthorized, client.get(READINESS_PATH).status)
        val response = client.get(READINESS_PATH) { bearerAuth("maintenance-secret") }
        val readiness = Json.decodeFromString<IncarnationMaintenanceReadinessDto>(response.bodyAsText())

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(20, readiness.schemaVersion)
        assertEquals(1L, readiness.activeIncarnationCount)
        assertEquals("READY", readiness.resetReadiness)
    }

    @Test
    fun `maintenance reset POST returns stable success DTO`() = testApplication {
        application { maintenanceApplication(SuccessMaintenance) }

        val response = client.post(RESET_PATH) {
            bearerAuth("maintenance-secret")
            contentType(ContentType.Application.Json)
            setBody(RESET_BODY)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("new", Json.decodeFromString<IncarnationMaintenanceResetResultDto>(response.bodyAsText()).activeIncarnationId)
    }

    @Test
    fun `maintenance export POST returns a completed manifest`() = testApplication {
        application { maintenanceApplication(SuccessMaintenance) }

        val response = client.post(EXPORT_PATH) {
            bearerAuth("maintenance-secret")
            contentType(ContentType.Application.Json)
            setBody("""{"incarnationId":"old","targetDirectory":"export"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("old", Json.decodeFromString<IncarnationExportManifest>(response.bodyAsText()).incarnationId)
    }

    @Test
    fun `maintenance reset POST returns durable resumed result`() = testApplication {
        application { maintenanceApplication(SuccessMaintenance) }

        val response = client.post(RESET_PATH) {
            bearerAuth("maintenance-secret")
            contentType(ContentType.Application.Json)
            setBody(RESET_BODY)
        }

        val result = Json.decodeFromString<IncarnationMaintenanceResetResultDto>(response.bodyAsText())
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("request", result.requestId)
        assertEquals("new", result.activeIncarnationId)
    }

    @Test
    fun `unexpected export and reset failures return generic traceable 500 DTOs`() = testApplication {
        application { maintenanceApplication(FailingMaintenance) }

        val export = client.post(EXPORT_PATH) {
            bearerAuth("maintenance-secret")
            contentType(ContentType.Application.Json)
            setBody("""{"incarnationId":"old","targetDirectory":"export"}""")
        }
        val reset = client.post(RESET_PATH) {
            bearerAuth("maintenance-secret")
            contentType(ContentType.Application.Json)
            setBody(RESET_BODY)
        }

        listOf(export, reset).forEach { response ->
            val text = response.bodyAsText()
            val error = Json.decodeFromString<InternalServerErrorDto>(text)
            assertEquals(HttpStatusCode.InternalServerError, response.status)
            assertEquals("INTERNAL_SERVER_ERROR", error.code)
            assertTrue(error.traceId.isNotBlank())
            assertFalse(text.contains("database-secret"))
            assertFalse(text.contains("qdrant-secret"))
        }
    }

    @Test
    fun `global status pages never expose unexpected exception text`() = testApplication {
        application {
            configureSerialization()
            configureStatusPages()
            routing { get("/unexpected") { error("global-secret") } }
        }

        val response = client.get("/unexpected")
        val text = response.bodyAsText()
        val error = Json.decodeFromString<InternalServerErrorDto>(text)

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals("INTERNAL_SERVER_ERROR", error.code)
        assertTrue(error.traceId.isNotBlank())
        assertFalse(text.contains("global-secret"))
    }

    @Test
    fun `maintenance reset maps malformed and rejected requests to stable non leaking 4xx`() = testApplication {
        application { maintenanceApplication(RejectingMaintenance) }

        val malformed = client.post(RESET_PATH) {
            bearerAuth("maintenance-secret")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.BadRequest, malformed.status)
        assertEquals("INVALID_REQUEST", Json.decodeFromString<IncarnationMaintenanceErrorDto>(malformed.bodyAsText()).code)

        val rejected = client.post(RESET_PATH) {
            bearerAuth("maintenance-secret")
            contentType(ContentType.Application.Json)
            setBody(RESET_BODY)
        }
        val body = rejected.bodyAsText()
        assertEquals(HttpStatusCode.Conflict, rejected.status)
        assertEquals("STALE_INCARNATION_ID", Json.decodeFromString<IncarnationMaintenanceErrorDto>(body).code)
        assertFalse(body.contains("database-secret"))
    }

    @Test
    fun `maintenance POST recovers after typed validation failures`() = testApplication {
        application { maintenanceApplication(RecoveringValidationMaintenance) }

        val invalidExport = client.post(EXPORT_PATH) {
            bearerAuth("maintenance-secret")
            contentType(ContentType.Application.Json)
            setBody("""{"incarnationId":"","targetDirectory":"existing"}""")
        }
        val invalidReset = client.post(RESET_PATH) {
            bearerAuth("maintenance-secret")
            contentType(ContentType.Application.Json)
            setBody("""{"incarnationId":"","requestId":"request","manifestPath":"manifest.json","confirmed":true,"personaMode":"legacy","personaStartSubState":"pre_command"}""")
        }
        listOf(invalidExport, invalidReset).forEach { response ->
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(
                "INVALID_REQUEST",
                Json.decodeFromString<IncarnationMaintenanceErrorDto>(response.bodyAsText()).code,
            )
        }

        val recoveredExport = client.post(EXPORT_PATH) {
            bearerAuth("maintenance-secret")
            contentType(ContentType.Application.Json)
            setBody("""{"incarnationId":"old","targetDirectory":"export"}""")
        }
        val recoveredReset = client.post(RESET_PATH) {
            bearerAuth("maintenance-secret")
            contentType(ContentType.Application.Json)
            setBody(RESET_BODY)
        }
        assertEquals(HttpStatusCode.OK, recoveredExport.status)
        assertEquals(HttpStatusCode.OK, recoveredReset.status)
    }

    private fun io.ktor.server.application.Application.maintenanceApplication(service: ServerIncarnationMaintenance) {
        attributes.put(MaintenanceAccessKey, MaintenanceAccess.enabled("maintenance-secret"))
        attributes.put(ServerIncarnationMaintenanceKey, service)
        configureSerialization()
        configureWebsockets()
        configureStatusPages()
        configureRouting()
    }

    private object FakeMaintenance : ServerIncarnationMaintenance {
        override suspend fun export(request: IncarnationMaintenanceExportDto): IncarnationExportManifest = error("unused")
        override suspend fun reset(request: IncarnationMaintenanceResetDto): IncarnationMaintenanceResetResultDto = error("unused")
        override suspend fun readiness() = IncarnationMaintenanceReadinessDto(
            schemaVersion = 20,
            activeIncarnationCount = 1,
            activeIncarnationId = "active",
            resetReadiness = "READY",
            incompleteResetCount = 0,
        )
    }

    private object SuccessMaintenance : ServerIncarnationMaintenance by FakeMaintenance {
        override suspend fun export(request: IncarnationMaintenanceExportDto) = IncarnationExportManifest(
            status = io.openeden.server.maintenance.IncarnationExportStatus.COMPLETED,
            incarnationId = request.incarnationId,
            exportedAtMs = 1L,
            transcriptCount = 0L,
            memoryCount = 0L,
            relationshipEventCount = 0L,
            files = emptyList(),
            payloadSha256 = "0".repeat(64),
            manifestSha256 = "1".repeat(64),
        )

        override suspend fun reset(request: IncarnationMaintenanceResetDto) = IncarnationMaintenanceResetResultDto(
            requestId = request.requestId,
            previousIncarnationId = request.incarnationId,
            activeIncarnationId = "new",
            lifecycle = "ACTIVE",
            personaMode = "GROWTH",
            personaStartSubState = "PRE_COMMAND",
            completedAtMs = 1L,
        )
    }

    private object FailingMaintenance : ServerIncarnationMaintenance by FakeMaintenance {
        override suspend fun export(request: IncarnationMaintenanceExportDto): IncarnationExportManifest =
            throw IllegalArgumentException("database-secret")

        override suspend fun reset(request: IncarnationMaintenanceResetDto): IncarnationMaintenanceResetResultDto =
            error("qdrant-secret")
    }

    private object RejectingMaintenance : ServerIncarnationMaintenance by FakeMaintenance {
        override suspend fun reset(request: IncarnationMaintenanceResetDto): IncarnationMaintenanceResetResultDto =
            throw IncarnationResetRejectedException(
                IncarnationResetRejection.STALE_INCARNATION_ID,
                "database-secret stale detail",
            )
    }

    private object RecoveringValidationMaintenance : ServerIncarnationMaintenance by SuccessMaintenance {
        override suspend fun export(request: IncarnationMaintenanceExportDto): IncarnationExportManifest {
            if (request.incarnationId.isBlank()) {
                throw IncarnationMaintenanceValidationException(IllegalArgumentException("blank incarnation"))
            }
            return SuccessMaintenance.export(request)
        }

        override suspend fun reset(request: IncarnationMaintenanceResetDto): IncarnationMaintenanceResetResultDto {
            if (request.incarnationId.isBlank()) {
                throw IncarnationMaintenanceValidationException(IllegalArgumentException("invalid legacy request"))
            }
            return SuccessMaintenance.reset(request)
        }
    }

    private companion object {
        const val READINESS_PATH = "/api/v1/maintenance/incarnation/readiness"
        const val EXPORT_PATH = "/api/v1/maintenance/incarnation/export"
        const val RESET_PATH = "/api/v1/maintenance/incarnation/reset"
        const val RESET_BODY = """{"incarnationId":"old","requestId":"request","manifestPath":"manifest.json","confirmed":true,"personaMode":"growth","personaStartSubState":"pre_command"}"""
    }
}
