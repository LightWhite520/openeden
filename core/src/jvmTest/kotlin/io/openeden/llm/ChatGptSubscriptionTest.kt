package io.openeden.llm

import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.openeden.prompt.PromptSegmentKind
import io.openeden.prompt.testBuiltPrompt
import io.openeden.transcript.PromptHistoryItem
import io.openeden.transcript.PromptHistorySnapshot
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class ChatGptSubscriptionTest {
    private val output = """{"internal_logic":"test","vector_delta":{"L":0.0,"P":0.0,"E":0.0,"S":0.0,"tau":0.0,"V":0.0,"M":0.0,"F":0.0},"response":"hello"}"""
    private val prompt = testBuiltPrompt(PromptSegmentKind.SYSTEM_CONTRACT to "contract", PromptSegmentKind.USER to "hello")

    @Test
    fun `subscription refreshes bearer and model and sends only supported responses fields`() = runTest {
        val bodies = mutableListOf<JsonObject>()
        val bearers = mutableListOf<String?>()
        var selected = "model-a"
        var token = "token-a"
        val client = OpenAiResponsesLlmClient.withChatGptSubscription(
            tokenProvider = { token }, model = "default",
            httpClient = OpenAiResponsesLlmClient.httpClient(MockEngine { request ->
                assertEquals("https://api.openai.com/v1/responses", request.url.toString())
                assertTrue(request.headers[HttpHeaders.Accept].orEmpty().contains("text/event-stream"))
                bodies += Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                bearers += request.headers[HttpHeaders.Authorization]
                respond(sse(), headers = if (bodies.size == 1) headersOf(HttpHeaders.ContentType, "text/event-stream") else headersOf())
            }, installTimeout = false),
        ).usingModelProvider { selected }
        client.use {
            assertEquals("hello", client.complete(prompt).response)
            token = "token-b"; selected = "model-b"
            assertEquals("hello", client.complete(prompt).response)
        }
        assertEquals<List<String?>>(listOf("Bearer token-a", "Bearer token-b"), bearers)
        assertEquals(listOf("model-a", "model-b"), bodies.map { it.getValue("model").jsonPrimitive.content })
        bodies.forEach { body ->
            assertEquals(64, body.getValue("prompt_cache_key").jsonPrimitive.content.length)
            assertFalse(body.getValue("store").jsonPrimitive.boolean)
            assertTrue(body.getValue("stream").jsonPrimitive.boolean)
            assertFalse("temperature" in body || "max_output_tokens" in body || "previous_response_id" in body)
            assertEquals("developer", body.getValue("input").jsonArray.first().jsonObject.getValue("role").jsonPrimitive.content)
        }
    }

    @Test
    fun `subscription routing key is stable across dynamic turns and unsupported key falls back once`() = runTest {
        val bodies = mutableListOf<JsonObject>()
        val sessions = mutableListOf<String?>()
        val client = OpenAiResponsesLlmClient.withChatGptSubscription({ "token" }, "model",
            httpClient = OpenAiResponsesLlmClient.httpClient(MockEngine { request ->
                bodies += Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                sessions += request.headers["session-id"]
                if (bodies.size == 1) respond("Unsupported parameter: prompt_cache_key", HttpStatusCode.BadRequest)
                else respond(sse(), headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
            }, installTimeout = false))
        client.use {
            assertEquals("hello", it.complete(prompt).response)
            assertEquals("hello", it.complete(prompt.appendDynamic(PromptSegmentKind.USER, "another turn")).response)
        }
        assertEquals(3, bodies.size)
        assertFalse("prompt_cache_key" in bodies[1])
        assertEquals(bodies[0]["prompt_cache_key"], bodies[2]["prompt_cache_key"])
        assertEquals<List<String?>>(List(3) { bodies[0].getValue("prompt_cache_key").jsonPrimitive.content }, sessions)
    }

    @Test
    fun `append only history and voice feedback retain routing while a new epoch invalidates it`() = runTest {
        val bodies = mutableListOf<JsonObject>()
        val sessions = mutableListOf<String?>()
        val client = OpenAiResponsesLlmClient.withChatGptSubscription({ "token" }, "model",
            httpClient = OpenAiResponsesLlmClient.httpClient(MockEngine { request ->
                bodies += Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                sessions += request.headers["session-id"]
                respond(sse(), headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
            }, installTimeout = false))
        fun history(count: Int, epoch: Long = 0) = testBuiltPrompt(
            PromptSegmentKind.SYSTEM_CONTRACT to "contract", PromptSegmentKind.USER to "current",
            promptHistory = PromptHistorySnapshot(
                mutableTail = (1..count).flatMap { i -> listOf(
                    PromptHistoryItem("user", "user-$i", "turn-$i", "u-$i"),
                    PromptHistoryItem("assistant", "assistant-$i", "turn-$i", "a-$i")) },
                sourceTurnIds = (1..count).map { "turn-$it" }.toSet(), cacheEpoch = epoch))
        client.use {
            it.complete(history(1).appendDynamic(PromptSegmentKind.TEMPORAL, "feedback-one"))
            it.complete(history(2).appendDynamic(PromptSegmentKind.TEMPORAL, "feedback-two"))
            it.complete(history(2, epoch = 1))
        }
        assertEquals(bodies[0]["prompt_cache_key"], bodies[1]["prompt_cache_key"])
        assertNotEquals(bodies[1]["prompt_cache_key"], bodies[2]["prompt_cache_key"])
        assertEquals<List<String?>>(bodies.map { it.getValue("prompt_cache_key").jsonPrimitive.content }, sessions)
        assertEquals(sessions[0], sessions[1])
        assertNotEquals(sessions[1], sessions[2])
        assertTrue(sessions.all { it != null && it.matches(Regex("[a-f0-9]{64}")) })
        assertEquals(bodies[0].getValue("input").jsonArray.take(3), bodies[1].getValue("input").jsonArray.take(3))
    }

    @Test
    fun `subscription rejects non streaming and unsuccessful terminal responses without fallback`() = runTest {
        listOf("response.failed", "response.incomplete", "error", "missing", "buffered").forEach { terminal ->
            var requests = 0
            val client = OpenAiResponsesLlmClient.withChatGptSubscription({ "token" }, "model",
                httpClient = OpenAiResponsesLlmClient.httpClient(MockEngine {
                    requests++
                    respond(if (terminal == "missing") "data: [DONE]\n\n" else "data: {\"type\":\"$terminal\"}\n\n",
                        headers = headersOf(HttpHeaders.ContentType, if (terminal == "buffered") "application/json" else "text/event-stream"))
                }, installTimeout = false))
            client.use { assertFailsWith<IllegalStateException> { client.complete(prompt) } }
            assertEquals(1, requests)
        }
    }

    @Test
    fun `evaluator stream requires completion and enforces bounded aggregate`() = runTest {
        assertEquals("{}", completedResponsesStream(ByteReadChannel("data: {\"type\":\"response.completed\",\"response\":{}}\n\n")))
        assertFailsWith<IllegalStateException> { completedResponsesStream(ByteReadChannel("data: {\"type\":\"response.incomplete\"}\n\n")) }
        assertFailsWith<IllegalStateException> { completedResponsesStream(ByteReadChannel("data: [DONE]\n\n")) }
        assertFailsWith<IllegalStateException> { completedResponsesStream(ByteReadChannel("x".repeat(100)), limit = 20) }
    }

    private fun sse() = "data: ${buildJsonObject { put("type", "response.output_text.delta"); put("delta", output) }}\n\n" +
        "data: {\"type\":\"response.completed\",\"response\":{}}\n\n"
}
