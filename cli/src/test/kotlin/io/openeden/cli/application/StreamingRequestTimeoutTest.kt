package io.openeden.cli.application

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.openeden.client.ChatStreamEvent
import io.openeden.client.OpenEdenServerClient
import java.net.InetSocketAddress
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class StreamingRequestTimeoutTest {
    @Test
    fun `slow chat stream survives request deadline while ordinary requests time out`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.use {
                exchange.requestBody.readBytes()
                // Simulate model latency, including the wait for response headers.
                Thread.sleep(400)
                val streaming = exchange.requestURI.path.endsWith("/stream")
                val body = if (streaming) {
                    "event: completed\ndata: {\"requestId\":\"slow\",\"status\":\"completed\"}\n\n"
                } else {
                    "{}"
                }
                exchange.responseHeaders.add("Content-Type", if (streaming) "text/event-stream" else "application/json")
                runCatching {
                    exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
                    exchange.responseBody.write(body.toByteArray())
                }
            }
        }
        server.start()
        val api = OpenEdenServerClient("http://127.0.0.1:${server.address.port}", HttpClient(CIO) {
            engine { requestTimeout = 100 }
            install(HttpTimeout) {
                requestTimeoutMillis = 100
                connectTimeoutMillis = 1_000
                socketTimeoutMillis = 3_000
            }
            install(ContentNegotiation) { json() }
        })
        try {
            withTimeout(5_000) {
                assertFalse(api.health())
                assertEquals(
                    listOf(ChatStreamEvent.Completed("slow", "completed")),
                    api.chatStream("test", "hello", "slow-test").toList(),
                )
            }
        } finally {
            api.close()
            server.stop(0)
        }
    }
}
