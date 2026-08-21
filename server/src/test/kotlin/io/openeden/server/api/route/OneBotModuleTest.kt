package io.openeden.server.api.route

import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.openeden.onebot.config.OneBotConfig
import io.openeden.onebot.connection.OneBotConnectionRegistry
import io.openeden.onebot.egress.OneBotActionSender
import io.openeden.onebot.ingress.OneBotAdapter
import io.openeden.onebot.ingress.OneBotMessageHandler
import io.openeden.onebot.ingress.OneBotMessageResult
import io.openeden.server.bootstrap.OneBotAdapterKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class OneBotModuleTest {
    @Test
    fun `configured adapter is installed from application attributes`() = testApplication {
        val adapter = adapter()
        application {
            install(WebSockets)
            attributes.put(OneBotAdapterKey, adapter)
            configureOneBot()
        }
        val client = createClient { install(ClientWebSockets) }
        try {
            client.webSocket("/onebot/v11", request = {
                header(HttpHeaders.Authorization, "Bearer secret")
                header("X-Self-ID", "10001")
            }) {
                send(Frame.Text(privateMessageJson()))
                val action = Json.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
                assertEquals("send_private_msg", action.getValue("action").jsonPrimitive.content)
                val echo = action.getValue("echo").jsonPrimitive.content
                send(Frame.Text("""{"status":"ok","retcode":0,"echo":"$echo"}"""))
            }
        } finally {
            adapter.shutdown()
        }
    }

    @Test
    fun `missing adapter attribute installs no OneBot route`() = testApplication {
        application {
            install(WebSockets)
            configureOneBot()
        }
        val client = createClient { install(ClientWebSockets) }

        assertEquals(HttpStatusCode.NotFound, client.get("/onebot/v11").status)
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
}
