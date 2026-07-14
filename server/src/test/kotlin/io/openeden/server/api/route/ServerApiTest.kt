package io.openeden.server.api.route

import io.openeden.server.api.dto.ChatResponseDto
import io.openeden.server.api.dto.PublicStateDto
import io.openeden.server.api.plugin.configureSerialization
import io.openeden.server.api.plugin.configureStatusPages
import io.openeden.server.bootstrap.SessionStateStoreKey
import io.openeden.runtime.session.MutableSessionStateStore
import io.openeden.runtime.session.SessionStateStore
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ServerApiTest {
    @Test
    fun `health endpoint reports ready`() = testApplication {
        application {
            configureSerialization()
            configureWebsockets()
            configureStatusPages()
            configureRouting()
        }

        val response = client.get("/health")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"status\":\"ready\""))
    }

    @Test
    fun `chat endpoint returns only public response fields`() = testApplication {
        application {
            configureSerialization()
            configureWebsockets()
            configureStatusPages()
            configureRouting()
        }

        val response = client.post("/api/v1/chat") {
            contentType(ContentType.Application.Json)
            setBody("""{"userId":"local","text":"hello"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.decodeFromString<ChatResponseDto>(response.bodyAsText())
        assertTrue(body.requestId.startsWith("req_"))
        assertEquals("completed", body.status)
        assertNotNull(body.response)
        assertFalse(response.bodyAsText().contains("evolutionIndex"))
        assertFalse(response.bodyAsText().contains("snapshot_8D"))
        assertFalse(response.bodyAsText().contains("promptPreview"))
        assertFalse(response.bodyAsText().contains("traceTags"))
    }

    @Test
    fun `chat endpoint rejects blank text`() = testApplication {
        application {
            configureSerialization()
            configureWebsockets()
            configureStatusPages()
            configureRouting()
        }

        val response = client.post("/api/v1/chat") {
            contentType(ContentType.Application.Json)
            setBody("""{"userId":"local","text":"  "}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `state endpoint reads the server owned session store`() = testApplication {
        val store = MutableSessionStateStore()
        store.write(SessionStateStore.neutral("CLI:local"))
        application {
            attributes.put(SessionStateStoreKey, store)
            configureSerialization()
            configureWebsockets()
            configureStatusPages()
            configureRouting()
        }

        val response = client.get("/api/v1/state?userId=local")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.decodeFromString<PublicStateDto>(response.bodyAsText())
        assertEquals("CLI:local", body.sessionId)
        assertEquals("ready", body.status)
        assertEquals(0.0f, body.omega)
        assertFalse(body.shockActive)
    }
}
