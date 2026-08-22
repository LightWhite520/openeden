package io.openeden.llm

import io.openeden.prompt.BuiltPrompt
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OpenAiResponsesLlmClientTest {
    @Test
    fun `parses prompt caching modes and detects supported model families`() {
        assertEquals(OpenAiPromptCachingMode.AUTO, OpenAiPromptCachingMode.parse("auto"))
        assertEquals(OpenAiPromptCachingMode.EXPLICIT, OpenAiPromptCachingMode.parse("EXPLICIT"))
        assertEquals(OpenAiPromptCachingMode.DISABLED, OpenAiPromptCachingMode.parse("off"))
        assertTrue(supportsExplicitPromptCaching("gpt-5.6-luna"))
        assertTrue(supportsExplicitPromptCaching("gpt-6"))
        assertTrue(!supportsExplicitPromptCaching("gpt-5.5"))
        assertFailsWith<IllegalArgumentException> { OpenAiPromptCachingMode.parse("invalid") }
    }

    @Test
    fun `streams strict structured output without exposing private fields`() = runTest {
        var requestBody = ""
        val first = """{"internal_logic":"private","vector_delta":{"L":0.0,"P":0.0,"E":0.0,"S":0.0,"tau":0.0,"V":0.0,"M":0.0,"F":0.0},"response":"你"""
        val second = """好"}"""
        val engine = MockEngine { request ->
            requestBody = request.body.toByteArray().decodeToString()
            respond(
                content = buildString {
                    append("data: ${Json.encodeToString(mapOf("type" to "response.output_text.delta", "delta" to first))}\n\n")
                    append("data: ${Json.encodeToString(mapOf("type" to "response.output_text.delta", "delta" to second))}\n\n")
                    append("data: {\"type\":\"response.completed\",\"response\":{\"usage\":{\"input_tokens\":9000,\"input_tokens_details\":{\"cached_tokens\":6500,\"cache_write_tokens\":2500}}}}\n\n")
                },
                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
            )
        }
        val client = OpenAiResponsesLlmClient(
            apiKey = "sk-test",
            model = "gpt-5.5",
            httpClient = OpenAiResponsesLlmClient.httpClient(engine, installTimeout = false),
        )

        val events = client.stream(
            prompt = BuiltPrompt("system", "persona", "user"),
            generationSettings = LlmGenerationSettings(
                temperature = 0.85f,
                verbosity = LlmVerbosity.LOW,
            ),
        ).toList()

        assertEquals(listOf("你", "好"), events.filterIsInstance<LlmStreamEvent.ResponseDelta>().map { it.text })
        assertEquals("你好", assertIs<LlmStreamEvent.Completed>(events.last()).output.response)
        assertEquals(9_000, assertIs<LlmStreamEvent.Completed>(events.last()).output.cacheMetrics?.inputTokens)
        assertEquals(6_500, assertIs<LlmStreamEvent.Completed>(events.last()).output.cacheMetrics?.cachedInputTokens)
        assertEquals(2_500, assertIs<LlmStreamEvent.Completed>(events.last()).output.cacheMetrics?.cacheWriteTokens)
        val body = Json.parseToJsonElement(requestBody).jsonObject
        assertEquals(true, body.getValue("stream").jsonPrimitive.content.toBoolean())
        assertEquals(0.85f, body.getValue("temperature").jsonPrimitive.float)
        assertEquals("low", body.getValue("text").jsonObject.getValue("verbosity").jsonPrimitive.content)
        val schemaProperties = body.getValue("text").jsonObject
            .getValue("format").jsonObject
            .getValue("schema").jsonObject
            .getValue("properties").jsonObject
        assertEquals(listOf("internal_logic", "vector_delta", "response"), schemaProperties.keys.toList())
    }

    @Test
    fun `completes schema valid provider output when object fields are reordered`() = runTest {
        val structured =
            """{"internal_logic":"private","response":"你好","vector_delta":{"E":0.0,"F":0.0,"L":0.0,"M":0.0,"P":0.0,"S":0.0,"V":0.0,"tau":0.0}}"""
        val engine = MockEngine {
            respond(
                content = buildString {
                    append("data: ${Json.encodeToString(mapOf("type" to "response.output_text.delta", "delta" to structured))}\n\n")
                    append("data: {\"type\":\"response.completed\"}\n\n")
                },
                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
            )
        }
        val client = OpenAiResponsesLlmClient(
            apiKey = "sk-test",
            model = "gpt-5.5",
            httpClient = OpenAiResponsesLlmClient.httpClient(engine, installTimeout = false),
        )

        val events = client.stream(BuiltPrompt("system", "persona", "user")).toList()

        assertEquals(listOf("你好"), events.filterIsInstance<LlmStreamEvent.ResponseDelta>().map { it.text })
        assertEquals("你好", assertIs<LlmStreamEvent.Completed>(events.last()).output.response)
    }

    @Test
    fun `sends prompt layers to responses api and parses output json`() = runTest {
        var requestBody = ""
        var requestUrl = ""
        val engine = MockEngine { request ->
            requestUrl = request.url.toString()
            requestBody = request.body.toByteArray().decodeToString()
            assertEquals("Bearer sk-test", request.headers[HttpHeaders.Authorization])
            respond(
                content = """
                    {
                      "output_text": "{\"internal_logic\":\"logic\",\"vector_delta\":{\"L\":0.0,\"P\":0.1,\"E\":0.0,\"S\":0.0,\"tau\":0.0,\"V\":0.0,\"M\":0.0,\"F\":0.0},\"response\":\"你好\"}"
                      ,"usage":{"input_tokens":9000,"input_tokens_details":{"cached_tokens":6500}}
                    }
                """.trimIndent(),
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = OpenAiResponsesLlmClient(
            apiKey = "sk-test",
            model = "gpt-5.5",
            baseUrl = "https://relay.example.com/v1",
            httpClient = OpenAiResponsesLlmClient.httpClient(engine, installTimeout = false),
        )

        val output = client.complete(
            prompt = BuiltPrompt(
                systemText = "system",
                personaText = "persona",
                userText = "user",
                contextText = "context",
            ),
            generationSettings = LlmGenerationSettings(
                temperature = 0.85f,
                verbosity = LlmVerbosity.LOW,
                maxOutputTokens = 32_000,
            ),
        )

        assertEquals("logic", output.internalLogic)
        assertEquals(0.1f, output.vectorDelta.getValue("P"))
        assertEquals("你好", output.response)
        assertEquals(9_000, output.cacheMetrics?.inputTokens)
        assertEquals(6_500, output.cacheMetrics?.cachedInputTokens)

        val body = Json.parseToJsonElement(requestBody).jsonObject
        assertEquals("https://relay.example.com/v1/responses", requestUrl)
        assertEquals("gpt-5.5", body.getValue("model").jsonPrimitive.content)
        val input = body.getValue("input").jsonArray
        assertEquals(4, input.size)
        assertEquals("system", input[0].jsonObject.getValue("role").jsonPrimitive.content)
        assertEquals("system", input[0].jsonObject.getValue("content").jsonPrimitive.content)
        assertEquals("developer", input[1].jsonObject.getValue("role").jsonPrimitive.content)
        assertEquals("persona", input[1].jsonObject.getValue("content").jsonPrimitive.content)
        assertEquals("developer", input[2].jsonObject.getValue("role").jsonPrimitive.content)
        assertEquals("context", input[2].jsonObject.getValue("content").jsonPrimitive.content)
        assertEquals("user", input[3].jsonObject.getValue("role").jsonPrimitive.content)
        assertEquals("user", input[3].jsonObject.getValue("content").jsonPrimitive.content)
        val format = body.getValue("text").jsonObject.getValue("format").jsonObject
        assertEquals("json_schema", format.getValue("type").jsonPrimitive.content)
        assertEquals(0.85f, body.getValue("temperature").jsonPrimitive.float)
        assertEquals("low", body.getValue("text").jsonObject.getValue("verbosity").jsonPrimitive.content)
        assertEquals(32_000, body.getValue("max_output_tokens").jsonPrimitive.int)
        assertEquals("medium", body.getValue("reasoning").jsonObject.getValue("effort").jsonPrimitive.content)
    }

    @Test
    fun `uses explicit stable persona breakpoint for gpt 5 6 and keeps dynamic context after it`() = runTest {
        var requestBody = ""
        val engine = MockEngine { request ->
            requestBody = request.body.toByteArray().decodeToString()
            respond(
                content = """
                    {"output_text":"{\"internal_logic\":\"logic\",\"vector_delta\":{\"L\":0.0,\"P\":0.0,\"E\":0.0,\"S\":0.0,\"tau\":0.0,\"V\":0.0,\"M\":0.0,\"F\":0.0},\"response\":\"你好\"}"}
                """.trimIndent(),
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = OpenAiResponsesLlmClient(
            apiKey = "sk-test",
            model = "gpt-5.6-luna",
            httpClient = OpenAiResponsesLlmClient.httpClient(engine, installTimeout = false),
        )

        client.complete(
            BuiltPrompt(
                systemText = "stable system",
                personaText = "stable persona",
                contextText = "dynamic state",
                userText = "current user",
            ),
        )

        val body = Json.parseToJsonElement(requestBody).jsonObject
        assertTrue(body.getValue("prompt_cache_key").jsonPrimitive.content.length >= 32)
        assertEquals("explicit", body.getValue("prompt_cache_options").jsonObject.getValue("mode").jsonPrimitive.content)
        val input = body.getValue("input").jsonArray
        val personaContent = input[1].jsonObject.getValue("content").jsonArray
        assertEquals("stable persona", personaContent[0].jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals(
            "explicit",
            personaContent[0].jsonObject.getValue("prompt_cache_breakpoint").jsonObject
                .getValue("mode").jsonPrimitive.content,
        )
        assertEquals("dynamic state", input[2].jsonObject.getValue("content").jsonPrimitive.content)
        assertEquals("current user", input[3].jsonObject.getValue("content").jsonPrimitive.content)
    }

    @Test
    fun `uses relay compatible cache options without a breakpoint for custom provider in auto mode`() = runTest {
        val body = captureCachingRequest(
            baseUrl = "https://relay.example.com/v1",
            mode = OpenAiPromptCachingMode.AUTO,
        )

        val key = assertIs<JsonPrimitive>(body.getValue("prompt_cache_key"))
        assertTrue(key.isString)
        assertTrue(key.content.isNotBlank())
        assertEquals(
            "explicit",
            body.getValue("prompt_cache_options").jsonObject.getValue("mode").jsonPrimitive.content,
        )
        assertEquals(
            "stable persona",
            assertIs<JsonPrimitive>(body.getValue("input").jsonArray[1].jsonObject.getValue("content")).content,
        )
    }

    @Test
    fun `uses breakpoint only for the exact OpenAI endpoint in auto mode`() = runTest {
        val exactOpenAiBody = captureCachingRequest(
            baseUrl = "https://api.openai.com/v1",
            mode = OpenAiPromptCachingMode.AUTO,
        )
        val lookalikeBody = captureCachingRequest(
            baseUrl = "https://api.openai.com.example.org/v1",
            mode = OpenAiPromptCachingMode.AUTO,
        )

        val exactPersonaContent = exactOpenAiBody.getValue("input").jsonArray[1].jsonObject
            .getValue("content").jsonArray
        assertEquals("input_text", exactPersonaContent[0].jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(
            "explicit",
            exactPersonaContent[0].jsonObject.getValue("prompt_cache_breakpoint")
                .jsonObject.getValue("mode").jsonPrimitive.content,
        )
        val lookalikeKey = assertIs<JsonPrimitive>(lookalikeBody.getValue("prompt_cache_key"))
        assertTrue(lookalikeKey.isString)
        assertTrue(lookalikeKey.content.isNotBlank())
        assertEquals(
            "explicit",
            lookalikeBody.getValue("prompt_cache_options").jsonObject.getValue("mode").jsonPrimitive.content,
        )
        assertEquals(
            "stable persona",
            assertIs<JsonPrimitive>(
                lookalikeBody.getValue("input").jsonArray[1].jsonObject.getValue("content"),
            ).content,
        )
    }

    @Test
    fun `sends breakpoint for custom provider in explicit mode`() = runTest {
        val body = captureCachingRequest(
            baseUrl = "https://relay.example.com/v1",
            mode = OpenAiPromptCachingMode.EXPLICIT,
        )

        val personaContent = body.getValue("input").jsonArray[1].jsonObject.getValue("content").jsonArray
        assertEquals(
            "explicit",
            personaContent[0].jsonObject.getValue("prompt_cache_breakpoint")
                .jsonObject.getValue("mode").jsonPrimitive.content,
        )
    }

    @Test
    fun `omits caching controls and keeps persona content as a string when disabled`() = runTest {
        val body = captureCachingRequest(
            baseUrl = "https://relay.example.com/v1",
            mode = OpenAiPromptCachingMode.DISABLED,
        )

        assertTrue("prompt_cache_key" !in body)
        assertTrue("prompt_cache_options" !in body)
        assertTrue("prompt_cache_breakpoint" !in Json.encodeToString(body))
        assertEquals(
            "stable persona",
            assertIs<JsonPrimitive>(body.getValue("input").jsonArray[1].jsonObject.getValue("content")).content,
        )
    }

    @Test
    fun `derives the same cache key for changing suffixes with the same stable prefix`() = runTest {
        val requestBodies = mutableListOf<String>()
        val engine = MockEngine { request ->
            requestBodies += request.body.toByteArray().decodeToString()
            respond(
                content = """
                    {"output_text":"{\"internal_logic\":\"logic\",\"vector_delta\":{\"L\":0.0,\"P\":0.0,\"E\":0.0,\"S\":0.0,\"tau\":0.0,\"V\":0.0,\"M\":0.0,\"F\":0.0},\"response\":\"你好\"}"}
                """.trimIndent(),
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = OpenAiResponsesLlmClient(
            apiKey = "sk-test",
            model = "gpt-5.6-luna",
            httpClient = OpenAiResponsesLlmClient.httpClient(engine, installTimeout = false),
        )

        client.complete(BuiltPrompt("stable system", "stable persona", "first user", "first context"))
        client.complete(BuiltPrompt("stable system", "stable persona", "second user", "second context"))

        val first = Json.parseToJsonElement(requestBodies[0]).jsonObject
        val second = Json.parseToJsonElement(requestBodies[1]).jsonObject
        assertEquals(first.getValue("prompt_cache_key"), second.getValue("prompt_cache_key"))
    }

    @Test
    fun `keeps provider output when buffered cache usage is invalid`() = runTest {
        val engine = MockEngine {
            respond(
                content = """
                    {
                      "output_text":"{\"internal_logic\":\"logic\",\"vector_delta\":{\"L\":0.0,\"P\":0.0,\"E\":0.0,\"S\":0.0,\"tau\":0.0,\"V\":0.0,\"M\":0.0,\"F\":0.0},\"response\":\"ok\"}",
                      "usage":{"input_tokens":100,"input_tokens_details":{"cached_tokens":200}}
                    }
                """.trimIndent(),
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = OpenAiResponsesLlmClient(
            apiKey = "sk-test",
            model = "gpt-5.5",
            httpClient = OpenAiResponsesLlmClient.httpClient(engine, installTimeout = false),
        )

        val output = client.complete(BuiltPrompt("system", "persona", "user"))

        assertEquals("ok", output.response)
        assertEquals(null, output.cacheMetrics)
    }

    @Test
    fun `omits unset max output tokens from responses request`() = runTest {
        var requestBody = ""
        val engine = MockEngine { request ->
            requestBody = request.body.toByteArray().decodeToString()
            respond(
                content = "{\"output_text\":\"{\\\"internal_logic\\\":\\\"logic\\\",\\\"vector_delta\\\":{\\\"L\\\":0.0,\\\"P\\\":0.0,\\\"E\\\":0.0,\\\"S\\\":0.0,\\\"tau\\\":0.0,\\\"V\\\":0.0,\\\"M\\\":0.0,\\\"F\\\":0.0},\\\"response\\\":\\\"ok\\\"}\"}",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = OpenAiResponsesLlmClient(
            apiKey = "sk-test",
            model = "gpt-5.5",
            httpClient = OpenAiResponsesLlmClient.httpClient(engine, installTimeout = false),
        )

        client.complete(
            prompt = BuiltPrompt("system", "persona", "user"),
            generationSettings = LlmGenerationSettings(
                temperature = 0.65f,
                verbosity = LlmVerbosity.HIGH,
            ),
        )

        val body = Json.parseToJsonElement(requestBody).jsonObject
        assertEquals("high", body.getValue("text").jsonObject.getValue("verbosity").jsonPrimitive.content)
        assertEquals(null, body["max_output_tokens"])
    }

    @Test
    fun `sends configured reasoning effort`() = runTest {
        var requestBody = ""
        val engine = MockEngine { request ->
            requestBody = request.body.toByteArray().decodeToString()
            respond(
                content = "{\"output_text\":\"{\\\"internal_logic\\\":\\\"logic\\\",\\\"vector_delta\\\":{\\\"L\\\":0.0,\\\"P\\\":0.0,\\\"E\\\":0.0,\\\"S\\\":0.0,\\\"tau\\\":0.0,\\\"V\\\":0.0,\\\"M\\\":0.0,\\\"F\\\":0.0},\\\"response\\\":\\\"ok\\\"}\"}",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = OpenAiResponsesLlmClient(
            apiKey = "sk-test",
            model = "gpt-5.5",
            reasoningEffort = ReasoningEffort.HIGH,
            httpClient = OpenAiResponsesLlmClient.httpClient(engine, installTimeout = false),
        )

        client.complete(BuiltPrompt("system", "persona", "user"))

        val body = Json.parseToJsonElement(requestBody).jsonObject
        assertEquals("high", body.getValue("reasoning").jsonObject.getValue("effort").jsonPrimitive.content)
    }

    @Test
    fun `throws clear error on non successful provider response`() = runTest {
        val engine = MockEngine {
            respondError(HttpStatusCode.Unauthorized, "bad key")
        }
        val client = OpenAiResponsesLlmClient(
            apiKey = "sk-test",
            model = "gpt-5.5",
            httpClient = OpenAiResponsesLlmClient.httpClient(engine, installTimeout = false),
        )

        val error = assertFailsWith<IllegalStateException> {
            client.complete(BuiltPrompt("system", "persona", "user"))
        }

        assertEquals("OpenAI Responses API request failed: 401 Unauthorized: bad key", error.message)
    }

    private suspend fun captureCachingRequest(
        baseUrl: String,
        mode: OpenAiPromptCachingMode,
    ): JsonObject {
        var requestBody = ""
        val engine = MockEngine { request ->
            requestBody = request.body.toByteArray().decodeToString()
            respond(
                content = """
                    {"output_text":"{\"internal_logic\":\"logic\",\"vector_delta\":{\"L\":0.0,\"P\":0.0,\"E\":0.0,\"S\":0.0,\"tau\":0.0,\"V\":0.0,\"M\":0.0,\"F\":0.0},\"response\":\"ok\"}"}
                """.trimIndent(),
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = OpenAiResponsesLlmClient(
            apiKey = "sk-test",
            model = "gpt-5.6-luna",
            baseUrl = baseUrl,
            httpClient = OpenAiResponsesLlmClient.httpClient(engine, installTimeout = false),
            promptCachingMode = mode,
            defaultGenerationSettings = LlmGenerationSettings.Default,
        )

        try {
            client.complete(
                BuiltPrompt(
                    systemText = "stable system",
                    personaText = "stable persona",
                    userText = "current user",
                    contextText = "dynamic state",
                ),
            )
        } finally {
            client.close()
        }

        return Json.parseToJsonElement(requestBody).jsonObject
    }
}
