package io.openeden.llm

import io.openeden.prompt.BuiltPrompt
import io.openeden.prompt.ConversationCacheIdentity
import io.openeden.prompt.PromptSegmentKind
import io.openeden.prompt.testBuiltPrompt
import io.openeden.transcript.ConversationTurn
import io.openeden.transcript.PromptHistorySerializer
import io.openeden.transcript.PromptHistorySnapshot
import io.openeden.transcript.PromptHistorySummary
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenAiResponsesLlmClientTest {
    @Test
    fun `marks input token usage without cache details as unobservable`() = runTest {
        val engine = MockEngine {
            respond(
                content = """
                    {
                      "output_text":"{\"internal_logic\":\"logic\",\"vector_delta\":{\"L\":0.0,\"P\":0.0,\"E\":0.0,\"S\":0.0,\"tau\":0.0,\"V\":0.0,\"M\":0.0,\"F\":0.0},\"response\":\"ok\"}",
                      "usage":{"input_tokens":100}
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

        val output = client.complete(simplePrompt())

        assertEquals(CacheMetricAvailability.UNOBSERVABLE, output.cacheMetrics?.availability)
    }

    @Test
    fun `parses explicit cache policies without model heuristics`() {
        assertEquals(OpenAiCachePolicy.OFFICIAL_EXPLICIT, OpenAiCachePolicy.parse("official_explicit"))
        assertEquals(OpenAiCachePolicy.RELAY_APPEND_ONLY, OpenAiCachePolicy.parse("RELAY_APPEND_ONLY"))
        assertEquals(OpenAiCachePolicy.OBSERVE_ONLY, OpenAiCachePolicy.parse("observe_only"))
        assertEquals(OpenAiCachePolicy.CACHE_DISABLED, OpenAiCachePolicy.parse("cache_disabled"))
        assertFailsWith<IllegalArgumentException> { OpenAiPromptCachingMode.parse("invalid") }
    }

    @Test
    fun `parses and conservatively applies legacy cache policies`() {
        val auto = OpenAiPromptCachingMode.parse("AUTO")
        val explicit = OpenAiPromptCachingMode.parse("explicit")
        val disabled = OpenAiPromptCachingMode.parse("disabled")

        assertEquals("AUTO", auto.name)
        assertEquals(
            OpenAiRequestCacheMetadata(cacheKey = true, cacheOptions = false, breakpoint = false),
            auto.requestMetadata(explicitCapabilities()),
        )
        assertEquals("EXPLICIT", explicit.name)
        assertEquals(
            OpenAiRequestCacheMetadata(cacheKey = true, cacheOptions = true, breakpoint = true),
            explicit.requestMetadata(explicitCapabilities()),
        )
        assertEquals("DISABLED", disabled.name)
        assertEquals(OpenAiRequestCacheMetadata.None, disabled.requestMetadata(explicitCapabilities()))
        assertFalse(disabled.observesUsage())
    }

    @Test
    fun `raw prompt fingerprints cannot bind changed stable content to a stale cache key`() = runTest {
        suspend fun cacheKey(system: String): String {
            val canonical = cachingPrompt(system = system)
            val forged = BuiltPrompt(
                segments = canonical.segments.map { segment ->
                    if (segment.kind == PromptSegmentKind.SYSTEM_CONTRACT) {
                        segment.copy(fingerprint = "stale-caller-fingerprint")
                    } else {
                        segment
                    }
                },
                cacheIdentity = "stale-caller-cache-identity",
                conversationCacheIdentity = canonical.conversationCacheIdentity,
            )
            return captureCachingRequest(
                policy = OpenAiCachePolicy.RELAY_APPEND_ONLY,
                capabilities = cacheKeyCapabilities(),
                prompt = forged,
            ).body.getValue("prompt_cache_key").jsonPrimitive.content
        }

        assertNotEquals(cacheKey("stable system one"), cacheKey("stable system two"))
    }

    @Test
    fun `official explicit requires positive capability evidence`() = runTest {
        val withoutEvidence = captureCachingRequest(
            baseUrl = "https://api.openai.com/v1",
            policy = OpenAiCachePolicy.OFFICIAL_EXPLICIT,
            capabilities = unavailableCapabilities(),
        ).body
        assertCacheMetadataAbsent(withoutEvidence)

        val withEvidence = captureCachingRequest(
            baseUrl = "https://api.openai.com/v1",
            policy = OpenAiCachePolicy.OFFICIAL_EXPLICIT,
            capabilities = explicitCapabilities(),
        ).body
        assertTrue(withEvidence.getValue("prompt_cache_key").jsonPrimitive.content.isNotBlank())
        assertEquals(
            "explicit",
            withEvidence.getValue("prompt_cache_options").jsonObject.getValue("mode").jsonPrimitive.content,
        )
        val anchor = withEvidence.getValue("input").jsonArray[2].jsonObject.getValue("content").jsonArray[0].jsonObject
        assertEquals(
            "explicit",
            anchor.getValue("prompt_cache_breakpoint").jsonObject.getValue("mode").jsonPrimitive.content,
        )
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
            prompt = simplePrompt(),
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

        val events = client.stream(simplePrompt()).toList()

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
            prompt = testBuiltPrompt(
                PromptSegmentKind.SYSTEM_CONTRACT to "system",
                PromptSegmentKind.PERSONA to "persona",
                PromptSegmentKind.INCARNATION_ANCHOR to "incarnation",
                PromptSegmentKind.BIO to "context",
                PromptSegmentKind.USER to "user",
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
        assertEquals(5, input.size)
        assertEquals("system", input[0].jsonObject.getValue("role").jsonPrimitive.content)
        assertEquals("system", input[0].jsonObject.getValue("content").jsonPrimitive.content)
        assertEquals("developer", input[1].jsonObject.getValue("role").jsonPrimitive.content)
        assertEquals("persona", input[1].jsonObject.getValue("content").jsonPrimitive.content)
        assertEquals("developer", input[2].jsonObject.getValue("role").jsonPrimitive.content)
        assertEquals("incarnation", input[2].jsonObject.getValue("content").jsonPrimitive.content)
        assertEquals("developer", input[3].jsonObject.getValue("role").jsonPrimitive.content)
        assertEquals("context", input[3].jsonObject.getValue("content").jsonPrimitive.content)
        assertEquals("user", input[4].jsonObject.getValue("role").jsonPrimitive.content)
        assertEquals("user", input[4].jsonObject.getValue("content").jsonPrimitive.content)
        val format = body.getValue("text").jsonObject.getValue("format").jsonObject
        assertEquals("json_schema", format.getValue("type").jsonPrimitive.content)
        assertEquals(0.85f, body.getValue("temperature").jsonPrimitive.float)
        assertEquals("low", body.getValue("text").jsonObject.getValue("verbosity").jsonPrimitive.content)
        assertEquals(32_000, body.getValue("max_output_tokens").jsonPrimitive.int)
        assertEquals("medium", body.getValue("reasoning").jsonObject.getValue("effort").jsonPrimitive.content)
    }

    @Test
    fun `serializes summary and mixed-role utf8 history in exact wire order`() = runTest {
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
        val historyItems = PromptHistorySerializer().createItems(
            listOf(
                ConversationTurn(
                    turnId = "tail-turn",
                    incarnationId = "incarnation-a",
                    sessionId = "CLI:local",
                    platform = "CLI",
                    scopeId = "local",
                    userId = "user-1",
                    userText = "历史用户🙂\n第二行",
                    assistantText = "历史助手回答：潮声。",
                    completedAtMs = 1L,
                ),
            ),
        )
        val history = PromptHistorySnapshot(
            summary = PromptHistorySummary(
                text = "较早的记忆：海边🙂",
                sourceTurnIds = setOf("summary-turn"),
                fingerprint = "summary-fingerprint",
                serializerVersion = 2,
            ),
            mutableTail = historyItems,
            sourceTurnIds = setOf("summary-turn", "tail-turn"),
            cacheEpoch = 4L,
        )
        val client = OpenAiResponsesLlmClient(
            apiKey = "sk-test",
            model = "gpt-5.5",
            httpClient = OpenAiResponsesLlmClient.httpClient(engine, installTimeout = false),
        )

        client.complete(
            testBuiltPrompt(
                PromptSegmentKind.SYSTEM_CONTRACT to "system",
                PromptSegmentKind.PERSONA to "persona",
                PromptSegmentKind.INCARNATION_ANCHOR to "incarnation",
                PromptSegmentKind.BIO to "bio",
                PromptSegmentKind.USER to "当前问题",
                promptHistory = history,
            ),
        )

        val input = Json.parseToJsonElement(requestBody).jsonObject.getValue("input").jsonArray
        val roles = input.map { it.jsonObject.getValue("role").jsonPrimitive.content }
        val contents = input.map { it.jsonObject.getValue("content").jsonPrimitive.content }
        assertEquals(
            listOf("system", "developer", "developer", "developer", "user", "assistant", "developer", "user"),
            roles,
        )
        val expectedContents = listOf(
            "system",
            "persona",
            "incarnation",
            "较早的记忆：海边🙂",
            "历史用户🙂\n第二行",
            "历史助手回答：潮声。",
            "bio",
            "当前问题",
        )
        assertEquals(expectedContents, contents)
        expectedContents.zip(contents).forEach { (expected, actual) ->
            assertContentEquals(expected.encodeToByteArray(), actual.encodeToByteArray())
        }
    }

    @Test
    fun `official explicit keeps dynamic context after the capability gated breakpoint`() = runTest {
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
            cachePolicy = OpenAiCachePolicy.OFFICIAL_EXPLICIT,
            capabilityProvider = capabilityProvider(explicitCapabilities()),
            cacheKeyContext = cacheKeyContext(),
        )

        client.complete(
            testBuiltPrompt(
                PromptSegmentKind.SYSTEM_CONTRACT to "stable system",
                PromptSegmentKind.PERSONA to "stable persona",
                PromptSegmentKind.INCARNATION_ANCHOR to "stable incarnation",
                PromptSegmentKind.BIO to "dynamic state",
                PromptSegmentKind.USER to "current user",
            ),
        )

        val body = Json.parseToJsonElement(requestBody).jsonObject
        assertTrue(body.getValue("prompt_cache_key").jsonPrimitive.content.length >= 32)
        assertEquals("explicit", body.getValue("prompt_cache_options").jsonObject.getValue("mode").jsonPrimitive.content)
        val input = body.getValue("input").jsonArray
        val anchorContent = input[2].jsonObject.getValue("content").jsonArray
        assertEquals("stable incarnation", anchorContent[0].jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals(
            "explicit",
            anchorContent[0].jsonObject.getValue("prompt_cache_breakpoint").jsonObject
                .getValue("mode").jsonPrimitive.content,
        )
        assertEquals("dynamic state", input[3].jsonObject.getValue("content").jsonPrimitive.content)
        assertEquals("current user", input[4].jsonObject.getValue("content").jsonPrimitive.content)
    }

    @Test
    fun `relay append mode preserves exact wire order and omits unsupported cache metadata`() = runTest {
        val captured = captureCachingRequest(
            baseUrl = "https://relay.example.com/v1",
            policy = OpenAiCachePolicy.RELAY_APPEND_ONLY,
            capabilities = unavailableCapabilities(),
        )
        val body = captured.body
        assertCacheMetadataAbsent(body)
        val input = body.getValue("input").jsonArray
        assertEquals(
            listOf("system", "developer", "developer", "developer", "user", "assistant", "developer", "user"),
            input.map { it.jsonObject.getValue("role").jsonPrimitive.content },
        )
        assertEquals(
            listOf(
                "stable system", "stable persona", "stable incarnation", "history summary", "history user",
                "history assistant", "dynamic state", "current user",
            ),
            input.map { it.jsonObject.getValue("content").jsonPrimitive.content },
        )
    }

    @Test
    fun `observe only reports provider usage while cache disabled suppresses cache metrics`() = runTest {
        val observed = captureCachingRequest(policy = OpenAiCachePolicy.OBSERVE_ONLY).output
        val disabled = captureCachingRequest(policy = OpenAiCachePolicy.CACHE_DISABLED).output

        assertEquals(CacheMetricAvailability.REPORTED, observed.cacheMetrics?.availability)
        assertEquals(90L, observed.cacheMetrics?.cachedInputTokens)
        assertNull(disabled.cacheMetrics)
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
            cachePolicy = OpenAiCachePolicy.RELAY_APPEND_ONLY,
            capabilityProvider = capabilityProvider(cacheKeyCapabilities()),
            cacheKeyContext = cacheKeyContext(),
        )

        client.complete(
            testBuiltPrompt(
                PromptSegmentKind.SYSTEM_CONTRACT to "stable system",
                PromptSegmentKind.PERSONA to "stable persona",
                PromptSegmentKind.INCARNATION_ANCHOR to "stable incarnation",
                PromptSegmentKind.BIO to "first context",
                PromptSegmentKind.USER to "first user",
            ),
        )
        client.complete(
            testBuiltPrompt(
                PromptSegmentKind.SYSTEM_CONTRACT to "stable system",
                PromptSegmentKind.PERSONA to "stable persona",
                PromptSegmentKind.INCARNATION_ANCHOR to "stable incarnation",
                PromptSegmentKind.BIO to "second context",
                PromptSegmentKind.USER to "second user",
            ),
        )

        val first = Json.parseToJsonElement(requestBodies[0]).jsonObject
        val second = Json.parseToJsonElement(requestBodies[1]).jsonObject
        assertEquals(first.getValue("prompt_cache_key"), second.getValue("prompt_cache_key"))
    }

    @Test
    fun `same prompt and epoch use different cache keys for different conversation scopes`() = runTest {
        suspend fun key(sessionId: String): String = captureCachingRequest(
            policy = OpenAiCachePolicy.RELAY_APPEND_ONLY,
            capabilities = cacheKeyCapabilities(),
            prompt = cachingPrompt(
                conversationCacheIdentity = ConversationCacheIdentity.fromAuthoritativeSessionId(sessionId),
            ),
        ).body.getValue("prompt_cache_key").jsonPrimitive.content

        assertNotEquals(key("QQ:group-alpha"), key("QQ:group-beta"))
    }

    @Test
    fun `raw conversation and user identities are absent from request and opaque cache key`() = runTest {
        val rawScopeId = "private-sensitive-scope"
        val rawSessionId = "QQ:$rawScopeId"
        val rawUserId = "sensitive-user-identity"
        val history = PromptHistorySnapshot(
            mutableTail = PromptHistorySerializer().createItems(
                listOf(
                    ConversationTurn(
                        turnId = "opaque-metadata-turn",
                        incarnationId = "incarnation-a",
                        sessionId = rawSessionId,
                        platform = "QQ",
                        scopeId = rawScopeId,
                        userId = rawUserId,
                        userText = "ordinary prior message",
                        assistantText = "ordinary prior response",
                        completedAtMs = 1L,
                    ),
                ),
            ),
        )
        val captured = captureCachingRequest(
            policy = OpenAiCachePolicy.RELAY_APPEND_ONLY,
            capabilities = cacheKeyCapabilities(),
            prompt = cachingPrompt(
                user = "ordinary message",
                conversationCacheIdentity = ConversationCacheIdentity.fromAuthoritativeSessionId(rawSessionId),
                promptHistory = history,
            ),
        )
        val requestText = captured.body.toString()
        val cacheKey = captured.body.getValue("prompt_cache_key").jsonPrimitive.content

        assertFalse(rawSessionId in requestText)
        assertFalse(rawUserId in requestText)
        assertFalse(rawSessionId in cacheKey)
        assertFalse(rawUserId in cacheKey)
        assertTrue(cacheKey.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `cache key rotates only on stable revisions identity namespace model or provider policy`() = runTest {
        suspend fun key(
            prompt: BuiltPrompt = cachingPrompt(),
            model: String = "model-a",
            context: OpenAiCacheKeyContext = cacheKeyContext(),
        ): String = captureCachingRequest(
            policy = OpenAiCachePolicy.RELAY_APPEND_ONLY,
            capabilities = cacheKeyCapabilities(),
            model = model,
            prompt = prompt,
            cacheKeyContext = context,
        ).body.getValue("prompt_cache_key").jsonPrimitive.content

        val baseline = key()
        assertEquals(
            baseline,
            key(
                prompt = cachingPrompt(
                    bio = "changed bio",
                    relationship = "changed relationship",
                    rag = "changed rag",
                    temporal = "changed exact time and request id",
                    user = "changed user",
                ),
            ),
        )
        assertNotEquals(baseline, key(prompt = cachingPrompt(system = "system revision 2")))
        assertNotEquals(baseline, key(prompt = cachingPrompt(cacheEpoch = 2L)))
        assertNotEquals(baseline, key(context = cacheKeyContext(personaRevision = "persona-v2")))
        assertNotEquals(baseline, key(context = cacheKeyContext(systemSchemaRevision = "schema-v2")))
        assertNotEquals(baseline, key(context = cacheKeyContext(dialogueNamespace = "dialogue-v2")))
        assertNotEquals(baseline, key(context = cacheKeyContext(providerPolicyRevision = "provider-v2")))
        assertNotEquals(baseline, key(model = "model-b"))
    }

    @Test
    fun `unknown 502 is not retried`() = runTest {
        var requests = 0
        val engine = MockEngine {
            requests += 1
            if (requests == 1) respondError(HttpStatusCode.BadGateway, "upstream failure") else successResponse()
        }
        val client = officialClient(engine)

        assertFailsWith<IllegalStateException> { client.complete(simplePrompt()) }

        assertEquals(1, requests)
    }

    @Test
    fun `recognized unsupported cache field 4xx retries once without metadata`() = runTest {
        val bodies = mutableListOf<JsonObject>()
        val engine = MockEngine { request ->
            bodies += Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
            if (bodies.size == 1) {
                respondError(HttpStatusCode.BadRequest, "{\"error\":{\"message\":\"Unsupported field prompt_cache_options\"}}")
            } else {
                successResponse()
            }
        }
        val client = officialClient(engine)

        assertEquals("ok", client.complete(simplePrompt()).response)

        assertEquals(2, bodies.size)
        assertTrue("prompt_cache_options" in bodies[0])
        assertCacheMetadataAbsent(bodies[1])
    }

    @Test
    fun `does not retry after SSE response bytes start`() = runTest {
        var requests = 0
        val engine = MockEngine {
            requests += 1
            if (requests == 1) {
                respond(
                    content = "data: {\"type\":\"error\",\"error\":{\"message\":\"unsupported prompt_cache_options\"}}\n\n",
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
                )
            } else successResponse()
        }
        val client = officialClient(engine)

        assertFailsWith<IllegalStateException> { client.stream(simplePrompt()).toList() }

        assertEquals(1, requests)
    }

    @Test
    fun `buffered and SSE usage parsing are identical and malformed usage is tolerated`() = runTest {
        val usage = "\"usage\":{\"input_tokens\":100,\"input_tokens_details\":{\"cached_tokens\":90,\"cache_write_tokens\":10}}"
        val bufferedEngine = MockEngine { successResponse(extraRootFields = usage) }
        val sseEngine = MockEngine {
            respond(
                content = buildString {
                    append("data: {\"type\":\"response.output_text.delta\",\"delta\":")
                    append(Json.encodeToString(structuredOutput()))
                    append("}\n\n")
                    append("data: {\"type\":\"response.completed\",\"response\":{$usage}}\n\n")
                },
                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
            )
        }
        val buffered = observeClient(bufferedEngine).complete(simplePrompt())
        val streamed = assertIs<LlmStreamEvent.Completed>(observeClient(sseEngine).stream(simplePrompt()).toList().last()).output
        assertEquals(buffered.cacheMetrics, streamed.cacheMetrics)

        val malformedBuffered = MockEngine { successResponse(extraRootFields = "\"usage\":{\"input_tokens\":\"bad\"}") }
        val malformedBufferedOutput = observeClient(malformedBuffered).complete(simplePrompt())
        assertEquals("ok", malformedBufferedOutput.response)
        assertNull(malformedBufferedOutput.cacheMetrics)
        val malformedSse = MockEngine {
            respond(
                content = "data: {\"type\":\"response.output_text.delta\",\"delta\":${Json.encodeToString(structuredOutput())}}\n\n" +
                    "data: {\"type\":\"response.completed\",\"response\":{\"usage\":{\"input_tokens\":\"bad\"}}}\n\n",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
            )
        }
        val malformedStreamedOutput =
            assertIs<LlmStreamEvent.Completed>(observeClient(malformedSse).stream(simplePrompt()).toList().last()).output
        assertEquals("ok", malformedStreamedOutput.response)
        assertNull(malformedStreamedOutput.cacheMetrics)
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

        val output = client.complete(simplePrompt())

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
            prompt = simplePrompt(),
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

        client.complete(simplePrompt())

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
            client.complete(simplePrompt())
        }

        assertEquals("OpenAI Responses API request failed: 401 Unauthorized: bad key", error.message)
    }

    private suspend fun captureCachingRequest(
        baseUrl: String = "https://relay.example.com/v1",
        policy: OpenAiCachePolicy,
        capabilities: OpenAiProviderCapabilities = unavailableCapabilities(),
        model: String = "gpt-5.6-luna",
        prompt: BuiltPrompt = relayPrompt(),
        cacheKeyContext: OpenAiCacheKeyContext = cacheKeyContext(),
    ): CapturedRequest {
        var requestBody = ""
        val engine = MockEngine { request ->
            requestBody = request.body.toByteArray().decodeToString()
            successResponse(
                extraRootFields = "\"usage\":{\"input_tokens\":100,\"input_tokens_details\":{\"cached_tokens\":90,\"cache_write_tokens\":10}}",
            )
        }
        val client = OpenAiResponsesLlmClient(
            apiKey = "sk-test",
            model = model,
            baseUrl = baseUrl,
            httpClient = OpenAiResponsesLlmClient.httpClient(engine, installTimeout = false),
            cachePolicy = policy,
            capabilityProvider = capabilityProvider(capabilities),
            cacheKeyContext = cacheKeyContext,
            defaultGenerationSettings = LlmGenerationSettings.Default,
        )

        val output = try {
            client.complete(prompt)
        } finally {
            client.close()
        }

        return CapturedRequest(Json.parseToJsonElement(requestBody).jsonObject, output)
    }

    private fun officialClient(engine: MockEngine) = OpenAiResponsesLlmClient(
        apiKey = "sk-test",
        model = "gpt-5.6-luna",
        baseUrl = "https://api.openai.com/v1",
        httpClient = OpenAiResponsesLlmClient.httpClient(engine, installTimeout = false),
        cachePolicy = OpenAiCachePolicy.OFFICIAL_EXPLICIT,
        capabilityProvider = capabilityProvider(explicitCapabilities()),
        cacheKeyContext = cacheKeyContext(),
        defaultGenerationSettings = LlmGenerationSettings.Default,
    )

    private fun observeClient(engine: MockEngine) = OpenAiResponsesLlmClient(
        apiKey = "sk-test",
        model = "model-a",
        baseUrl = "https://relay.example.com/v1",
        httpClient = OpenAiResponsesLlmClient.httpClient(engine, installTimeout = false),
        cachePolicy = OpenAiCachePolicy.OBSERVE_ONLY,
        capabilityProvider = capabilityProvider(unavailableCapabilities()),
        cacheKeyContext = cacheKeyContext(),
        defaultGenerationSettings = LlmGenerationSettings.Default,
    )

    private fun capabilityProvider(capabilities: OpenAiProviderCapabilities) =
        OpenAiCapabilityProvider { capabilities }

    private fun unavailableCapabilities() = OpenAiProviderCapabilities.unavailable(0L)

    private fun cacheKeyCapabilities() = unavailableCapabilities().copy(cacheKeyAccepted = true)

    private fun explicitCapabilities() = OpenAiProviderCapabilities(
        basicResponses = true,
        cacheKeyAccepted = true,
        cacheOptionsAccepted = true,
        explicitBreakpointAccepted = true,
        previousResponseAccepted = false,
        metricAvailability = CacheMetricAvailability.REPORTED,
        expiresAtMs = Long.MAX_VALUE,
    )

    private fun cacheKeyContext(
        providerPolicyRevision: String = "provider-v1",
        systemSchemaRevision: String = "schema-v1",
        personaRevision: String = "persona-v1:PRE_COMMAND",
        dialogueNamespace: String = "dialogue-v1",
    ) = OpenAiCacheKeyContext(
        providerPolicyRevision = providerPolicyRevision,
        systemSchemaRevision = systemSchemaRevision,
        personaRevision = personaRevision,
        dialogueNamespace = dialogueNamespace,
    )

    private fun assertCacheMetadataAbsent(body: JsonObject) {
        assertFalse("prompt_cache_key" in body)
        assertFalse("prompt_cache_options" in body)
        body.getValue("input").jsonArray.forEach { message ->
            val content = message.jsonObject.getValue("content")
            assertIs<JsonPrimitive>(content)
        }
    }

    private fun cachingPrompt(
        system: String = "stable system",
        bio: String = "dynamic state",
        relationship: String = "relationship",
        rag: String = "rag",
        temporal: String = "temporal",
        user: String = "current user",
        cacheEpoch: Long = 0L,
        conversationCacheIdentity: ConversationCacheIdentity =
            ConversationCacheIdentity.fromAuthoritativeSessionId("CLI:local"),
        promptHistory: PromptHistorySnapshot = PromptHistorySnapshot(cacheEpoch = cacheEpoch),
    ) = testBuiltPrompt(
        PromptSegmentKind.SYSTEM_CONTRACT to system,
        PromptSegmentKind.PERSONA to "stable persona",
        PromptSegmentKind.INCARNATION_ANCHOR to "stable incarnation",
        PromptSegmentKind.BIO to bio,
        PromptSegmentKind.RELATIONSHIP to relationship,
        PromptSegmentKind.RAG to rag,
        PromptSegmentKind.TEMPORAL to temporal,
        PromptSegmentKind.USER to user,
        promptHistory = promptHistory,
        conversationCacheIdentity = conversationCacheIdentity,
    )

    private fun relayPrompt(): BuiltPrompt {
        val turn = ConversationTurn(
            turnId = "history-turn",
            incarnationId = "incarnation-a",
            sessionId = "CLI:local",
            platform = "CLI",
            scopeId = "local",
            userId = "opaque-user",
            userText = "history user",
            assistantText = "history assistant",
            completedAtMs = 1L,
        )
        return testBuiltPrompt(
            PromptSegmentKind.SYSTEM_CONTRACT to "stable system",
            PromptSegmentKind.PERSONA to "stable persona",
            PromptSegmentKind.INCARNATION_ANCHOR to "stable incarnation",
            PromptSegmentKind.BIO to "dynamic state",
            PromptSegmentKind.USER to "current user",
            promptHistory = PromptHistorySnapshot(
                summary = PromptHistorySummary(
                    text = "history summary",
                    sourceTurnIds = setOf("summary-turn"),
                    fingerprint = "summary-fingerprint",
                    serializerVersion = 2,
                ),
                mutableTail = PromptHistorySerializer().createItems(listOf(turn)),
                sourceTurnIds = setOf("summary-turn", "history-turn"),
                cacheEpoch = 1L,
            ),
        )
    }

    private fun structuredOutput() =
        """{"internal_logic":"logic","vector_delta":{"L":0.0,"P":0.0,"E":0.0,"S":0.0,"tau":0.0,"V":0.0,"M":0.0,"F":0.0},"response":"ok"}"""

    private fun MockRequestHandleScope.successResponse(extraRootFields: String? = null) = respond(
        content = buildString {
            append("{\"output_text\":")
            append(Json.encodeToString(structuredOutput()))
            extraRootFields?.let { append(',').append(it) }
            append('}')
        },
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )

    private data class CapturedRequest(val body: JsonObject, val output: LlmOutput)

    private fun simplePrompt(): BuiltPrompt = testBuiltPrompt(
        PromptSegmentKind.SYSTEM_CONTRACT to "system",
        PromptSegmentKind.PERSONA to "persona",
        PromptSegmentKind.INCARNATION_ANCHOR to "incarnation",
        PromptSegmentKind.USER to "user",
    )
}
