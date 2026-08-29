package io.openeden.server.maintenance

import io.openeden.server.vector.qdrant.QdrantClient
import io.openeden.server.vector.qdrant.QdrantCollectionNaming
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class QdrantIncarnationProjectionEraserTest {
    @Test
    fun `erase waits and verifies configured and persisted projection models`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            val body = if (request.url.encodedPath.endsWith("/points/count")) {
                """{"result":{"count":0}}"""
            } else {
                """{"result":{"status":"acknowledged"}}"""
            }
            respond(body, HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
        }
        val client = QdrantClient("http://qdrant.test", timeoutMillis = 0, engine = engine)
        try {
            QdrantIncarnationProjectionEraser(
                client = client,
                naming = QdrantCollectionNaming("openeden_memory"),
                configuredModelId = "current-model",
            ).eraseAndVerify("incarnation-1", setOf("old-model"))

            assertEquals(4, requests.size)
            val deletes = requests.filter { it.url.encodedPath.endsWith("/points/delete") }
            assertEquals(2, deletes.size)
            assertTrue(deletes.all { it.url.parameters["wait"] == "true" })
            requests.forEach { request ->
                val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                assertTrue(body.contains("incarnation_id"))
                assertTrue(body.contains("incarnation-1"))
                assertTrue(body.contains("model_id"))
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun `nonzero verification count fails closed`() = runTest {
        val engine = MockEngine { request ->
            val body = if (request.url.encodedPath.endsWith("/points/count")) {
                """{"result":{"count":1}}"""
            } else {
                """{"result":{"status":"acknowledged"}}"""
            }
            respond(body, HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
        }
        val client = QdrantClient("http://qdrant.test", timeoutMillis = 0, engine = engine)
        try {
            assertFailsWith<IllegalStateException> {
                QdrantIncarnationProjectionEraser(
                    client,
                    QdrantCollectionNaming("openeden_memory"),
                    "current-model",
                ).eraseAndVerify("incarnation-1", emptySet())
            }
        } finally {
            client.close()
        }
    }
}
