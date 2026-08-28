package io.openeden.llm

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.utils.io.core.readBytes
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class OpenAiCapabilityProbe(
    private val apiKey: String,
    private val baseUrl: String,
    private val model: String,
    val routingFingerprint: String,
    private val httpClient: HttpClient = httpClient(CIO.create()),
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = DefaultTtlMs,
) : AutoCloseable {
    val cacheKey = OpenAiCapabilityCacheKey(baseUrl, model, routingFingerprint)

    init {
        require(ttlMs >= 0L) { "ttlMs must not be negative" }
    }

    suspend fun probe(): OpenAiProviderCapabilities = try {
        probeCanaries()
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Throwable) {
        OpenAiProviderCapabilities.unavailable(nowMs())
    }

    private suspend fun probeCanaries(): OpenAiProviderCapabilities {
        val basic = sendCanary()
        val cacheKey = sendCanary(promptCacheKey = CanaryCacheKey)
        val cacheOptions = sendCanary(
            promptCacheKey = CanaryCacheKey,
            cacheOptions = true,
        )
        val breakpoint = sendCanary(
            promptCacheKey = CanaryCacheKey,
            cacheOptions = true,
            breakpoint = true,
        )
        val metricSample = sendCanary(
            promptCacheKey = CanaryCacheKey,
            cacheOptions = true,
        )
        val previousResponse = basic.responseId?.let { responseId ->
            sendCanary(previousResponseId = responseId)
        }

        return OpenAiProviderCapabilities(
            basicResponses = basic.accepted,
            cacheKeyAccepted = cacheKey.accepted,
            cacheOptionsAccepted = cacheOptions.accepted,
            explicitBreakpointAccepted = breakpoint.accepted,
            previousResponseAccepted = previousResponse?.accepted == true,
            metricAvailability = if (cacheOptions.hasCacheMetrics && metricSample.hasCacheMetrics) {
                CacheMetricAvailability.REPORTED
            } else {
                CacheMetricAvailability.UNOBSERVABLE
            },
            expiresAtMs = nowMs() + ttlMs,
        )
    }

    private suspend fun sendCanary(
        promptCacheKey: String? = null,
        cacheOptions: Boolean = false,
        breakpoint: Boolean = false,
        previousResponseId: String? = null,
    ): CanaryResponse {
        val response = httpClient.post("${baseUrl.trimEnd('/')}/responses") {
            bearerAuth(apiKey)
            contentType(ContentType.Application.Json)
            setBody(
                JsonObject(
                    buildMap {
                        put("model", JsonPrimitive(model))
                        put("input", canaryInput(breakpoint))
                        put("max_output_tokens", JsonPrimitive(1))
                        put("stream", JsonPrimitive(false))
                        promptCacheKey?.let { put("prompt_cache_key", JsonPrimitive(it)) }
                        if (cacheOptions) {
                            put("prompt_cache_options", JsonObject(mapOf("mode" to JsonPrimitive("explicit"))))
                        }
                        previousResponseId?.let { put("previous_response_id", JsonPrimitive(it)) }
                    },
                ),
            )
        }
        if (response.status.value !in 200..299) return CanaryResponse.Rejected
        val body = boundedBody(response.bodyAsChannel())
        val objectBody = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
        val hasCacheMetrics = objectBody?.get("usage")?.let { usage ->
            runCatching { usage.jsonObject["input_tokens_details"]?.jsonObject != null }.getOrDefault(false)
        } ?: false
        return CanaryResponse(
            accepted = true,
            responseId = objectBody?.get("id")?.jsonPrimitive?.contentOrNull,
            hasCacheMetrics = hasCacheMetrics,
        )
    }

    private suspend fun boundedBody(channel: io.ktor.utils.io.ByteReadChannel): String {
        val bytes = channel.readRemaining(MaxResponseBodyBytes.toLong() + 1L).readBytes()
        check(bytes.size <= MaxResponseBodyBytes) { "OpenAI capability probe response exceeded limit" }
        return bytes.decodeToString()
    }

    private fun canaryInput(breakpoint: Boolean): JsonArray = JsonArray(
        listOf(
            JsonObject(
                mapOf(
                    "role" to JsonPrimitive("user"),
                    "content" to if (breakpoint) {
                        JsonArray(
                            listOf(
                                JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("input_text"),
                                        "text" to JsonPrimitive(CanaryPrompt),
                                        "prompt_cache_breakpoint" to JsonObject(
                                            mapOf("mode" to JsonPrimitive("explicit")),
                                        ),
                                    ),
                                ),
                            ),
                        )
                    } else {
                        JsonPrimitive(CanaryPrompt)
                    },
                ),
            ),
        ),
    )

    override fun close() = httpClient.close()

    companion object {
        const val DefaultTtlMs: Long = 15L * 60L * 1000L
        const val MaxResponseBodyBytes: Int = 16 * 1024

        fun httpClient(engine: HttpClientEngine, installTimeout: Boolean = true): HttpClient = HttpClient(engine) {
            if (installTimeout) {
                install(io.ktor.client.plugins.HttpTimeout) {
                    requestTimeoutMillis = 30_000
                    connectTimeoutMillis = 10_000
                    socketTimeoutMillis = 30_000
                }
            }
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; explicitNulls = false })
            }
        }

        private const val CanaryCacheKey = "openeden-capability-canary-v1"
        private const val CanaryPrompt = "openeden-capability-canary-v1"
    }
}

private data class CanaryResponse(
    val accepted: Boolean,
    val responseId: String? = null,
    val hasCacheMetrics: Boolean = false,
) {
    companion object {
        val Rejected = CanaryResponse(accepted = false)
    }
}
