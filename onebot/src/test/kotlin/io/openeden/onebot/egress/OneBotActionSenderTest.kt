package io.openeden.onebot.egress

import io.openeden.onebot.connection.OneBotConnectionRegistry
import io.openeden.onebot.connection.OneBotSocket
import io.openeden.onebot.protocol.OneBotActionResponse
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OneBotActionSenderTest {
    @Test
    fun `sends private and group actions with correlated text segments`() = runTest {
        val registry = OneBotConnectionRegistry("10001")
        lateinit var sender: OneBotActionSender
        val sent = mutableListOf<String>()
        val socket = FakeSocket { payload ->
            sent += payload
            val echo = Json.parseToJsonElement(payload).jsonObject.getValue("echo").jsonPrimitive.content
            sender.complete(OneBotActionResponse("ok", 0, echo), registry.snapshot()!!.epoch)
        }
        registry.register("10001", socket)
        sender = OneBotActionSender(registry, timeoutMs = 1_000L, maxRetries = 0)

        sender.sendPrivate("22", "hello")
        sender.sendGroup("33", "group hello")

        val privateAction = Json.parseToJsonElement(sent[0]).jsonObject
        assertEquals("send_private_msg", privateAction.getValue("action").jsonPrimitive.content)
        assertEquals("22", privateAction.getValue("params").jsonObject["user_id"]!!.jsonPrimitive.content)
        assertEquals(
            "hello",
            privateAction.getValue("params").jsonObject["message"]!!.jsonArray.single()
                .jsonObject.getValue("data").jsonObject.getValue("text").jsonPrimitive.content,
        )
        val groupAction = Json.parseToJsonElement(sent[1]).jsonObject
        assertEquals("send_group_msg", groupAction.getValue("action").jsonPrimitive.content)
        assertEquals("33", groupAction.getValue("params").jsonObject["group_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `classifies rejected and timed out actions`() = runTest {
        val registry = OneBotConnectionRegistry("10001")
        lateinit var sender: OneBotActionSender
        val rejectingSocket = FakeSocket { payload ->
            val echo = Json.parseToJsonElement(payload).jsonObject.getValue("echo").jsonPrimitive.content
            sender.complete(OneBotActionResponse("failed", 100, echo), registry.snapshot()!!.epoch)
        }
        registry.register("10001", rejectingSocket)
        sender = OneBotActionSender(registry, timeoutMs = 1_000L, maxRetries = 0)

        val rejected = assertFailsWith<OneBotActionException> { sender.sendPrivate("22", "hello") }
        assertEquals(OneBotActionException.Category.REJECTED, rejected.category)

        val timeoutRegistry = OneBotConnectionRegistry("10001")
        timeoutRegistry.register("10001", FakeSocket())
        val timeoutSender = OneBotActionSender(timeoutRegistry, timeoutMs = 10L, maxRetries = 0)
        val timedOut = assertFailsWith<OneBotActionException> { timeoutSender.sendPrivate("22", "hello") }
        assertEquals(OneBotActionException.Category.TIMEOUT, timedOut.category)
    }

    @Test
    fun `does not send when required epoch was replaced`() = runTest {
        val registry = OneBotConnectionRegistry("10001")
        val firstSocket = FakeSocket()
        val first = registry.register("10001", firstSocket)
        val secondSocket = FakeSocket()
        registry.register("10001", secondSocket)
        val sender = OneBotActionSender(registry, timeoutMs = 100L, maxRetries = 0)

        val failure = assertFailsWith<OneBotActionException> {
            sender.sendPrivate("22", "hello", requiredEpoch = first.epoch)
        }
        assertEquals(OneBotActionException.Category.DISCONNECTED, failure.category)
        assertEquals(emptyList(), secondSocket.sent)
    }

    @Test
    fun `sends long replies as natural segments with asynchronous pacing`() = runTest {
        val registry = OneBotConnectionRegistry("10001")
        lateinit var sender: OneBotActionSender
        val sent = mutableListOf<String>()
        val socket = FakeSocket { payload ->
            sent += payload
            val echo = Json.parseToJsonElement(payload).jsonObject.getValue("echo").jsonPrimitive.content
            sender.complete(OneBotActionResponse("ok", 0, echo), registry.snapshot()!!.epoch)
        }
        registry.register("10001", socket)
        val pacingIndexes = mutableListOf<Int>()
        sender = OneBotActionSender(
            registry = registry,
            timeoutMs = 1_000L,
            maxRetries = 0,
            segmentDelay = { pacingIndexes += it },
        )

        val text = "第一段说明当前情况，先把已经确认的部分说清楚。\n\n第二段补充接下来的处理方式，我们可以继续观察结果。"
        sender.sendPrivate("22", text)

        assertEquals(2, sent.size)
        assertEquals(listOf(1), pacingIndexes)
        val sentText = sent.map {
            Json.parseToJsonElement(it).jsonObject.getValue("params").jsonObject
                .getValue("message").jsonArray.single().jsonObject
                .getValue("data").jsonObject.getValue("text").jsonPrimitive.content
        }
        assertEquals(text, sentText.joinToString(separator = ""))
        assertTrue(sentText.all(String::isNotBlank))
    }

    @Test
    fun `stops segmented delivery when the required connection epoch changes`() = runTest {
        val registry = OneBotConnectionRegistry("10001")
        lateinit var sender: OneBotActionSender
        val firstSocket = FakeSocket { payload ->
            val echo = Json.parseToJsonElement(payload).jsonObject.getValue("echo").jsonPrimitive.content
            sender.complete(OneBotActionResponse("ok", 0, echo), registry.snapshot()!!.epoch)
            registry.register("10001", FakeSocket())
        }
        val first = registry.register("10001", firstSocket)
        sender = OneBotActionSender(
            registry = registry,
            timeoutMs = 1_000L,
            maxRetries = 0,
            segmentDelay = {},
        )

        val failure = assertFailsWith<OneBotActionException> {
            sender.sendPrivate(
                "22",
                "第一段内容。\n\n第二段内容。",
                requiredEpoch = first.epoch,
            )
        }

        assertEquals(OneBotActionException.Category.DISCONNECTED, failure.category)
        assertEquals(1, firstSocket.sent.size)
    }

    private class FakeSocket(
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
