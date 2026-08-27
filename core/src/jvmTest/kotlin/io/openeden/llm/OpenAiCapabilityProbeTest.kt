package io.openeden.llm

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenAiCapabilityProbeTest {
    @Test
    fun `probe distinguishes accepted metadata from observable cache usage`() = runTest {
        val capabilities = probeAgainstScriptedServer(
            basic = 200,
            cacheKey = 200,
            cacheOptions = 200,
            breakpoint = 502,
            previousResponse = 200,
            usage = null,
        )

        assertTrue(capabilities.basicResponses)
        assertTrue(capabilities.cacheKeyAccepted)
        assertTrue(capabilities.cacheOptionsAccepted)
        assertFalse(capabilities.explicitBreakpointAccepted)
        assertTrue(capabilities.previousResponseAccepted)
        assertEquals(CacheMetricAvailability.UNOBSERVABLE, capabilities.metricAvailability)
    }

    @Test
    fun `probe sends five bounded synthetic request shapes`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            respond(
                content = """{"id":"canary-response","usage":{"input_tokens":8,"input_tokens_details":{"cached_tokens":4,"cache_write_tokens":4}}}""",
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val probe = OpenAiCapabilityProbe(
            apiKey = "secret-that-must-not-be-traced",
            baseUrl = "https://relay.example.test/v1",
            model = "test-model",
            routingFingerprint = "route-a",
            httpClient = OpenAiCapabilityProbe.httpClient(engine, installTimeout = false),
            nowMs = { 1_000L },
        )

        probe.probe()

        assertEquals(6, requests.size)
        val bodies = requests.map { Json.parseToJsonElement(it.body.toByteArray().decodeToString()).jsonObject }
        assertTrue(bodies.all { body -> body.getValue("input").toString().contains("openeden-capability-canary") })
        assertTrue(bodies.none { body -> body.toString().contains("secret-that-must-not-be-traced") })
        assertTrue("prompt_cache_key" !in bodies[0])
        assertTrue("prompt_cache_key" in bodies[1])
        assertTrue("prompt_cache_options" in bodies[2])
        assertTrue(bodies[3].getValue("input").toString().contains("prompt_cache_breakpoint"))
        assertEquals("canary-response", bodies[5].getValue("previous_response_id").jsonPrimitive.content)
    }

    @Test
    fun `probe records every accepted capability and reported cache metrics`() = runTest {
        val capabilities = probeAgainstScriptedServer(
            basic = 200,
            cacheKey = 200,
            cacheOptions = 200,
            breakpoint = 200,
            previousResponse = 200,
            usage = "{\"input_tokens\":8,\"input_tokens_details\":{\"cached_tokens\":4}}",
        )

        assertTrue(capabilities.basicResponses)
        assertTrue(capabilities.cacheKeyAccepted)
        assertTrue(capabilities.cacheOptionsAccepted)
        assertTrue(capabilities.explicitBreakpointAccepted)
        assertTrue(capabilities.previousResponseAccepted)
        assertEquals(CacheMetricAvailability.REPORTED, capabilities.metricAvailability)
        assertEquals(901_000L, capabilities.expiresAtMs)
    }

    @Test
    fun `capability cache reuses a nonexpired result only for the same provider route`() = runTest {
        var calls = 0
        var now = 1_000L
        val cache = OpenAiCapabilityCache(nowMs = { now })
        val key = OpenAiCapabilityCacheKey("https://relay.example.test/v1", "test-model", "route-a")
        val capability = OpenAiProviderCapabilities(
            basicResponses = true,
            cacheKeyAccepted = true,
            cacheOptionsAccepted = true,
            explicitBreakpointAccepted = false,
            previousResponseAccepted = false,
            metricAvailability = CacheMetricAvailability.UNOBSERVABLE,
            expiresAtMs = 2_000L,
        )

        assertEquals(capability, cache.getOrProbe(key) { calls += 1; capability })
        assertEquals(capability, cache.getOrProbe(key) { calls += 1; error("must not re-probe") })
        now = 2_000L
        assertEquals(capability, cache.getOrProbe(key) { calls += 1; capability })
        assertEquals(2, calls)
        assertEquals(null, cache.get(OpenAiCapabilityCacheKey(key.baseUrl, key.model, "route-b")))
    }

    private suspend fun probeAgainstScriptedServer(
        basic: Int,
        cacheKey: Int,
        cacheOptions: Int,
        breakpoint: Int,
        previousResponse: Int,
        usage: String?,
    ): OpenAiProviderCapabilities {
        val statuses = ArrayDeque(listOf(basic, cacheKey, cacheOptions, breakpoint, basic, previousResponse))
        val engine = MockEngine {
            val status = HttpStatusCode.fromValue(statuses.removeFirst())
            respond(
                content = """{"id":"canary-response"${usage?.let { ",\"usage\":$it" }.orEmpty()}}""",
                status = status,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        return OpenAiCapabilityProbe(
            apiKey = "test-key",
            baseUrl = "https://relay.example.test/v1",
            model = "test-model",
            routingFingerprint = "route-a",
            httpClient = OpenAiCapabilityProbe.httpClient(engine, installTimeout = false),
            nowMs = { 1_000L },
        ).probe()
    }
}
