package io.openeden.onebot.route

import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readReason
import io.ktor.websocket.readText
import io.openeden.onebot.config.OneBotConfig
import io.openeden.onebot.connection.OneBotConnectionRegistry
import io.openeden.onebot.egress.OneBotActionSender
import io.openeden.onebot.ingress.OneBotAdapter
import io.openeden.onebot.ingress.OneBotMessageHandler
import io.openeden.onebot.ingress.OneBotMessageResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OneBotReverseWebSocketRouteTest {
    @Test
    fun `rejects wrong bearer token without registering connection`() = testApplication {
        val adapter = adapter()
        application {
            install(WebSockets)
            routing { oneBotReverseWebSocket(adapter) }
        }
        val client = createClient { install(ClientWebSockets) }
        try {
            client.webSocket("/onebot/v11", request = {
                header(HttpHeaders.Authorization, "Bearer wrong")
                header("X-Self-ID", "10001")
            }) {
                assertPolicyClose()
            }
            assertNull(adapter.registry.snapshot())
        } finally {
            adapter.shutdown()
        }
    }

    @Test
    fun `rejects mismatched self id without registering connection`() = testApplication {
        val adapter = adapter()
        application {
            install(WebSockets)
            routing { oneBotReverseWebSocket(adapter) }
        }
        val client = createClient { install(ClientWebSockets) }
        try {
            client.webSocket("/onebot/v11", request = {
                header(HttpHeaders.Authorization, "Bearer secret")
                header("X-Self-ID", "different")
            }) {
                assertPolicyClose()
            }
            assertNull(adapter.registry.snapshot())
        } finally {
            adapter.shutdown()
        }
    }

    @Test
    fun `routes valid private message and clears connection on disconnect`() = testApplication {
        val adapter = adapter()
        application {
            install(WebSockets)
            routing { oneBotReverseWebSocket(adapter) }
        }
        val client = createClient { install(ClientWebSockets) }
        try {
            client.webSocket("/onebot/v11", request = {
                header(HttpHeaders.Authorization, "Bearer secret")
                header("X-Self-ID", "10001")
            }) {
                send(Frame.Text(privateMessageJson()))
                val action = (incoming.receive() as Frame.Text).readText()
                val actionJson = Json.parseToJsonElement(action).jsonObject
                assertEquals("send_private_msg", actionJson.getValue("action").jsonPrimitive.content)
                assertEquals("22", actionJson.getValue("params").jsonObject["user_id"]!!.jsonPrimitive.content)
                assertTrue(actionJson.containsKey("echo"))
                val echo = actionJson.getValue("echo").jsonPrimitive.content
                send(Frame.Text("""{"status":"ok","retcode":0,"echo":"$echo"}"""))
            }
            withTimeout(1_000L) {
                while (adapter.registry.snapshot() != null) delay(10)
            }
        } finally {
            adapter.shutdown()
        }
    }

    private fun adapter(): OneBotAdapter {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val registry = OneBotConnectionRegistry("10001")
        val sender = OneBotActionSender(registry, timeoutMs = 1_000L, maxRetries = 0)
        return OneBotAdapter(
            config = OneBotConfig(enabled = true, accessToken = "secret", botSelfId = "10001"),
            registry = registry,
            actions = sender,
            handler = OneBotMessageHandler { OneBotMessageResult("reply") },
            scope = scope,
        )
    }

    private fun privateMessageJson() =
        """{"self_id":10001,"post_type":"message","message_type":"private","message_id":7,"user_id":22,"message":"hello"}"""

    private suspend fun io.ktor.client.plugins.websocket.DefaultClientWebSocketSession.assertPolicyClose() {
        try {
            val close = incoming.receive() as Frame.Close
            assertTrue(close.readReason()?.code == CloseReason.Codes.VIOLATED_POLICY.code)
        } catch (_: ClosedReceiveChannelException) {
            // Ktor may close the client channel after delivering the close reason.
        }
    }
}
