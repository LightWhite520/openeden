package io.openeden.onebot.heartbeat

import io.openeden.onebot.connection.OneBotConnectionRegistry
import io.openeden.onebot.connection.OneBotSocket
import io.openeden.onebot.egress.OneBotActionException
import io.openeden.onebot.egress.OneBotActionSender
import io.openeden.onebot.protocol.OneBotActionResponse
import io.openeden.runtime.heartbeat.HeartbeatTarget
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OneBotHeartbeatDeliveryTest {
    @Test
    fun `delivers only to a connected QQ private owner`() = runTest {
        val registry = OneBotConnectionRegistry("10001")
        lateinit var sender: OneBotActionSender
        val socket = RecordingSocket { payload ->
            val echo = Json.parseToJsonElement(payload).jsonObject.getValue("echo").jsonPrimitive.content
            sender.complete(OneBotActionResponse("ok", 0, echo), registry.snapshot()!!.epoch)
        }
        registry.register("10001", socket)
        sender = OneBotActionSender(registry, timeoutMs = 1_000L, maxRetries = 0)
        val delivery = OneBotHeartbeatDelivery(registry, sender)

        assertFalse(delivery.isConnected(HeartbeatTarget("WEB", "22")))
        assertTrue(delivery.isConnected(HeartbeatTarget("QQ", "22")))
        delivery.deliver("QQ:22", HeartbeatTarget("QQ", "22"), shock = false, response = "ping")

        assertEquals(1, socket.sent.size)
        assertTrue(socket.sent.single().contains("send_private_msg"))
    }

    @Test
    fun `drops heartbeat delivery after the connection epoch is gone`() = runTest {
        val registry = OneBotConnectionRegistry("10001")
        registry.register("10001", RecordingSocket())
        val delivery = OneBotHeartbeatDelivery(
            registry,
            OneBotActionSender(registry, timeoutMs = 100L, maxRetries = 0),
        )
        registry.unregister(registry.snapshot()!!.epoch)

        assertFalse(delivery.isConnected(HeartbeatTarget("QQ", "22")))
        val failure = assertFailsWith<OneBotActionException> {
            delivery.deliver("QQ:22", HeartbeatTarget("QQ", "22"), shock = false, response = "ping")
        }
        assertEquals(OneBotActionException.Category.DISCONNECTED, failure.category)
    }

    private class RecordingSocket(
        private val onSend: suspend (String) -> Unit = {},
    ) : OneBotSocket {
        val sent = mutableListOf<String>()

        override suspend fun send(text: String) {
            sent += text
            onSend(text)
        }

        override suspend fun close(reason: String) = Unit
    }
}
