package io.openeden.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.contentType
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OpenEdenServerClientTest {
    @Test
    fun `health chat and state use the configured server url`() = runTest {
        val requests = mutableListOf<String>()
        val engine = MockEngine { request ->
            requests += request.url.toString()
            when (request.url.encodedPath) {
                "/health" -> respond("""{"status":"ready","service":"openeden-server"}""", headers = contentType())
                "/api/v1/chat" -> {
                    assertTrue(request.body.toByteArray().decodeToString().contains("\"text\":\"hello\""))
                    respond("""{"requestId":"req_1","status":"completed","response":"你好","error":null}""", headers = contentType())
                }
                "/api/v1/state" -> respond("""{"sessionId":"CLI:local","status":"ready","omega":0.2,"shockActive":false}""", headers = contentType())
                "/api/v1/chat/stream" -> {
                    assertTrue(request.body.toByteArray().decodeToString().contains("\"clientRequestId\":\"client_1\""))
                    respond(
                        """
                        event: accepted
                        data: {"requestId":"req_1"}

                        event: response.delta
                        data: {"text":"你好"}

                        event: completed
                        data: {"requestId":"req_1","status":"completed"}

                        """.trimIndent(),
                        headers = headersOf("Content-Type", ContentType.Text.EventStream.toString()),
                    )
                }
                else -> error("unexpected path: ${request.url.encodedPath}")
            }
        }
        val client = OpenEdenServerClient("http://127.0.0.1:8080/", HttpClient(engine) {
            install(ContentNegotiation) { json() }
        })

        assertEquals(true, client.health())
        assertEquals("你好", client.chat("local", "hello").response)
        assertEquals("CLI:local", client.state("local").sessionId)
        assertEquals(
            listOf(
                ChatStreamEvent.Accepted("req_1"),
                ChatStreamEvent.ResponseDelta("你好"),
                ChatStreamEvent.Completed("req_1", "completed"),
            ),
            client.chatStream("local", "hello", "client_1").toList(),
        )
        assertEquals(
            listOf(
                "http://127.0.0.1:8080/health",
                "http://127.0.0.1:8080/api/v1/chat",
                "http://127.0.0.1:8080/api/v1/state?userId=local",
                "http://127.0.0.1:8080/api/v1/chat/stream",
            ),
            requests,
        )
        client.close()
    }

    @Test
    fun `non successful response becomes typed client error`() = runTest {
        val engine = MockEngine {
            respondError(HttpStatusCode.ServiceUnavailable, "starting")
        }
        val client = OpenEdenServerClient("http://localhost:8080", HttpClient(engine) {
            install(ContentNegotiation) { json() }
        })

        val error = assertFailsWith<ServerClientException> { client.chat("local", "hello") }

        assertEquals(HttpStatusCode.ServiceUnavailable, error.status)
        assertTrue(error.message!!.contains("starting"))
        client.close()
    }
}

private fun contentType() = headersOf("Content-Type", ContentType.Application.Json.toString())
