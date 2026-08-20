package io.openeden.server.api.route

import io.openeden.runtime.session.MutableSessionStateStore
import io.openeden.server.api.dto.DiagnosticStateDto
import io.openeden.server.api.plugin.configureSerialization
import io.openeden.server.api.plugin.configureStatusPages
import io.openeden.server.bootstrap.SessionStateStoreKey
import io.openeden.server.bootstrap.VectorDatabaseStatusKey
import io.openeden.server.vector.QdrantCircuitBreaker
import io.openeden.server.vector.VectorDatabaseStatus
import io.openeden.server.vector.VectorDatabaseStatusProvider
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DiagnosticsApiTest {
    @Test
    fun `diagnostics are not found when disabled`() = testApplication {
        application {
            attributes.put(DiagnosticsAccessKey, DiagnosticsAccess.disabled())
            attributes.put(SessionStateStoreKey, MutableSessionStateStore())
            configureSerialization()
            configureWebsockets()
            configureStatusPages()
            configureRouting()
        }

        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/diagnostics?userId=local").status)
    }

    @Test
    fun `enabled diagnostics require bearer token and keep dissonance outside vector`() = testApplication {
        application {
            attributes.put(DiagnosticsAccessKey, DiagnosticsAccess.enabled("secret"))
            attributes.put(SessionStateStoreKey, MutableSessionStateStore())
            attributes.put(
                VectorDatabaseStatusKey,
                VectorDatabaseStatusProvider {
                    VectorDatabaseStatus(
                        collection = "openeden_memory_local_v1_a1b2c3d4",
                        circuit = QdrantCircuitBreaker.Snapshot(
                            state = QdrantCircuitBreaker.State.OPEN,
                            consecutiveFailures = 3,
                            openedAtMs = 100L,
                            lastSuccessAtMs = 90L,
                            lastFailure = null,
                        ),
                        fallbackActive = true,
                        lastTraceTag = "vector_db=QDRANT_FALLBACK",
                        pendingProjectionCount = 2,
                        totalNonReadyProjectionCount = 5,
                        lastErrorCategory = "TIMEOUT",
                        lastErrorAtMs = 100L,
                    )
                },
            )
            configureSerialization()
            configureWebsockets()
            configureStatusPages()
            configureRouting()
        }

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/diagnostics?userId=local").status)

        val response = client.get("/api/v1/diagnostics?userId=local") {
            bearerAuth("secret")
        }
        val body = Json.decodeFromString<DiagnosticStateDto>(response.bodyAsText())

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(8, body.vector.size)
        assertNotNull(body.derivedDissonance)
        assertEquals("QDRANT", body.vectorDatabase?.backend)
        assertEquals("openeden_memory_local_v1_a1b2c3d4", body.vectorDatabase?.collection)
        assertEquals("OPEN", body.vectorDatabase?.circuit)
        assertEquals(2L, body.vectorDatabase?.pendingProjectionCount)
        assertEquals(5L, body.vectorDatabase?.totalNonReadyProjectionCount)
        assertEquals(90L, body.vectorDatabase?.lastSuccessAtMs)
        assertEquals("TIMEOUT", body.vectorDatabase?.lastErrorCategory)
    }
}
