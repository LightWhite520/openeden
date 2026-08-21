package io.openeden.onebot.ingress

import io.openeden.onebot.config.OneBotConfig
import io.openeden.onebot.connection.OneBotConnectionRegistry
import io.openeden.onebot.connection.OneBotSocket
import io.openeden.onebot.egress.OneBotActionSender
import io.openeden.onebot.protocol.OneBotActionResponse
import io.openeden.runtime.pipeline.DevelopmentMessageRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OneBotAdapterTest {
    @Test
    fun `routes private and mentioned group messages through the handler`() = runTest {
        lateinit var sender: OneBotActionSender
        val registry = OneBotConnectionRegistry("10001")
        val socket = RecordingSocket { payload ->
            val echo = Json.parseToJsonElement(payload).jsonObject.getValue("echo").jsonPrimitive.content
            sender.complete(OneBotActionResponse("ok", 0, echo), registry.snapshot()!!.epoch)
        }
        val connection = registry.register("10001", socket)
        sender = OneBotActionSender(registry, timeoutMs = 1_000L, maxRetries = 0)
        val requests = mutableListOf<DevelopmentMessageRequest>()
        val adapter = OneBotAdapter(
            config = config(),
            registry = registry,
            actions = sender,
            handler = OneBotMessageHandler { request ->
                requests += request
                OneBotMessageResult("reply")
            },
            scope = this,
        )

        adapter.onText(privateMessageJson(), connection.epoch)
        adapter.onText(groupMessageJson(), connection.epoch)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("22", "22"), requests.map { it.userId })
        assertEquals(listOf("22", "33"), requests.map { it.scopeId })
        assertEquals(2, socket.sent.size)
        assertTrue(socket.sent[0].contains("send_private_msg"))
        assertTrue(socket.sent[1].contains("send_group_msg"))
        adapter.shutdown()
    }

    @Test
    fun `does not send blank results and traces queue overflow`() = runTest {
        val registry = OneBotConnectionRegistry("10001")
        val socket = RecordingSocket()
        val connection = registry.register("10001", socket)
        val traces = mutableListOf<String>()
        val adapter = OneBotAdapter(
            config = config(eventQueueCapacity = 1, eventWorkers = 1),
            registry = registry,
            actions = OneBotActionSender(registry, timeoutMs = 1_000L, maxRetries = 0),
            handler = OneBotMessageHandler { OneBotMessageResult(" ") },
            scope = this,
            onTrace = traces::add,
        )

        adapter.onText(privateMessageJson(messageId = 7), connection.epoch)
        adapter.onText(privateMessageJson(messageId = 8), connection.epoch)
        adapter.onText(privateMessageJson(messageId = 9), connection.epoch)
        testScheduler.advanceUntilIdle()

        assertTrue(traces.any { it == "onebot=QUEUE_OVERFLOW" })
        assertEquals(emptyList(), socket.sent)
        adapter.shutdown()
    }

    private fun config(
        eventQueueCapacity: Int = 64,
        eventWorkers: Int = 4,
    ) = OneBotConfig(
        enabled = true,
        accessToken = "secret",
        botSelfId = "10001",
        eventQueueCapacity = eventQueueCapacity,
        eventWorkers = eventWorkers,
    )

    private fun privateMessageJson(messageId: Int = 7) =
        """{"self_id":10001,"post_type":"message","message_type":"private","message_id":$messageId,"user_id":22,"message":"hello"}"""

    private fun groupMessageJson() =
        """{"self_id":10001,"post_type":"message","message_type":"group","message_id":8,"group_id":33,"user_id":22,"message":[{"type":"at","data":{"qq":"10001"}},{"type":"text","data":{"text":" hello"}}]}"""

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
