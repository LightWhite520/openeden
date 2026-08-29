package io.openeden.llm

import io.ktor.client.*
import io.ktor.client.engine.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.util.logging.*
import io.ktor.utils.io.*
import io.openeden.prompt.BuiltPrompt
import io.openeden.prompt.PromptSegmentKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.security.MessageDigest

private val log = KtorSimpleLogger("io.openeden.llm.OpenAiResponsesLlmClient")

class OpenAiResponsesLlmClient private constructor(
    private val apiKey: String,
    private val model: String,
    private val reasoningEffort: ReasoningEffort,
    private val baseUrl: String,
    private val httpClient: HttpClient,
    private val json: Json,
    private val defaultGenerationSettings: LlmGenerationSettings,
    private val cachePolicy: OpenAiCachePolicy,
    private val capabilityProvider: OpenAiCapabilityProvider,
    private val cacheKeyContext: OpenAiCacheKeyContext,
    constructorMarker: Unit,
) : StreamingLlmClient, AutoCloseable {
    constructor(
        apiKey: String,
        model: String,
        reasoningEffort: ReasoningEffort = ReasoningEffort.MEDIUM,
        baseUrl: String = "https://api.openai.com/v1",
        httpClient: HttpClient = httpClient(CIO.create()),
        json: Json = Json { ignoreUnknownKeys = true },
    ) : this(
        apiKey = apiKey,
        model = model,
        reasoningEffort = reasoningEffort,
        baseUrl = baseUrl,
        httpClient = httpClient,
        json = json,
        defaultGenerationSettings = LlmGenerationSettings.Default,
        cachePolicy = OpenAiCachePolicy.RELAY_APPEND_ONLY,
        capabilityProvider = unavailableCapabilityProvider(),
        cacheKeyContext = OpenAiCacheKeyContext.Default,
        constructorMarker = Unit,
    )

    constructor(
        apiKey: String,
        model: String,
        reasoningEffort: ReasoningEffort = ReasoningEffort.MEDIUM,
        baseUrl: String = "https://api.openai.com/v1",
        httpClient: HttpClient = httpClient(CIO.create()),
        json: Json = Json { ignoreUnknownKeys = true },
        defaultGenerationSettings: LlmGenerationSettings,
    ) : this(
        apiKey = apiKey,
        model = model,
        reasoningEffort = reasoningEffort,
        baseUrl = baseUrl,
        httpClient = httpClient,
        json = json,
        defaultGenerationSettings = defaultGenerationSettings,
        cachePolicy = OpenAiCachePolicy.RELAY_APPEND_ONLY,
        capabilityProvider = unavailableCapabilityProvider(),
        cacheKeyContext = OpenAiCacheKeyContext.Default,
        constructorMarker = Unit,
    )

    constructor(
        apiKey: String,
        model: String,
        reasoningEffort: ReasoningEffort,
        baseUrl: String,
        httpClient: HttpClient,
        json: Json,
        cachePolicy: OpenAiCachePolicy,
    ) : this(
        apiKey = apiKey,
        model = model,
        reasoningEffort = reasoningEffort,
        baseUrl = baseUrl,
        httpClient = httpClient,
        json = json,
        defaultGenerationSettings = LlmGenerationSettings.Default,
        cachePolicy = cachePolicy,
        capabilityProvider = unavailableCapabilityProvider(),
        cacheKeyContext = OpenAiCacheKeyContext.Default,
        constructorMarker = Unit,
    )

    constructor(
        apiKey: String,
        model: String,
        reasoningEffort: ReasoningEffort = ReasoningEffort.MEDIUM,
        baseUrl: String = "https://api.openai.com/v1",
        httpClient: HttpClient = httpClient(CIO.create()),
        json: Json = Json { ignoreUnknownKeys = true },
        cachePolicy: OpenAiCachePolicy,
        defaultGenerationSettings: LlmGenerationSettings,
    ) : this(
        apiKey = apiKey,
        model = model,
        reasoningEffort = reasoningEffort,
        baseUrl = baseUrl,
        httpClient = httpClient,
        json = json,
        defaultGenerationSettings = defaultGenerationSettings,
        cachePolicy = cachePolicy,
        capabilityProvider = unavailableCapabilityProvider(),
        cacheKeyContext = OpenAiCacheKeyContext.Default,
        constructorMarker = Unit,
    )

    constructor(
        apiKey: String,
        model: String,
        reasoningEffort: ReasoningEffort,
        baseUrl: String,
        httpClient: HttpClient,
        json: Json,
        defaultGenerationSettings: LlmGenerationSettings,
        cachePolicy: OpenAiCachePolicy,
    ) : this(
        apiKey = apiKey,
        model = model,
        reasoningEffort = reasoningEffort,
        baseUrl = baseUrl,
        httpClient = httpClient,
        json = json,
        defaultGenerationSettings = defaultGenerationSettings,
        cachePolicy = cachePolicy,
        capabilityProvider = unavailableCapabilityProvider(),
        cacheKeyContext = OpenAiCacheKeyContext.Default,
        constructorMarker = Unit,
    )

    constructor(
        apiKey: String,
        model: String,
        reasoningEffort: ReasoningEffort = ReasoningEffort.MEDIUM,
        baseUrl: String = "https://api.openai.com/v1",
        httpClient: HttpClient = httpClient(CIO.create()),
        json: Json = Json { ignoreUnknownKeys = true },
        cachePolicy: OpenAiCachePolicy,
        capabilityProvider: OpenAiCapabilityProvider,
        cacheKeyContext: OpenAiCacheKeyContext,
        defaultGenerationSettings: LlmGenerationSettings = LlmGenerationSettings.Default,
    ) : this(
        apiKey = apiKey,
        model = model,
        reasoningEffort = reasoningEffort,
        baseUrl = baseUrl,
        httpClient = httpClient,
        json = json,
        defaultGenerationSettings = defaultGenerationSettings,
        cachePolicy = cachePolicy,
        capabilityProvider = capabilityProvider,
        cacheKeyContext = cacheKeyContext,
        constructorMarker = Unit,
    )

    override val supportsStrictStructuredStreaming: Boolean = true

    override suspend fun complete(prompt: BuiltPrompt): LlmOutput = complete(prompt, defaultGenerationSettings)

    override suspend fun complete(prompt: BuiltPrompt, generationSettings: LlmGenerationSettings): LlmOutput {
        log.info("\nPrompt:\n${prompt.textPreview()}")
        val response = execute(prompt, generationSettings, stream = false)
        val llmOutput = parseBufferedResponse(response.bodyAsText())
        return llmOutput
    }

    override fun stream(prompt: BuiltPrompt): Flow<LlmStreamEvent> = stream(prompt, defaultGenerationSettings)

    override fun stream(prompt: BuiltPrompt, generationSettings: LlmGenerationSettings): Flow<LlmStreamEvent> = flow {
        log.info("\nPrompt:\n${prompt.textPreview()}")
        val response = execute(prompt, generationSettings, stream = true)
        if (response.contentType()?.withoutParameters() != ContentType.Text.EventStream) {
            emit(LlmStreamEvent.Completed(parseBufferedResponse(response.bodyAsText())))
            return@flow
        }

        val decoder = StrictOutputStreamDecoder(json)
        val emittedResponse = StringBuilder()
        val data = StringBuilder()
        var completed = false
        suspend fun consumeFrame() {
            if (data.isEmpty()) return
            val payload = data.toString()
            data.clear()
            if (payload == "[DONE]") return
            val event = try {
                json.parseToJsonElement(payload).jsonObject
            } catch (error: Throwable) {
                throw IllegalStateException("OpenAI Responses API returned malformed SSE data", error)
            }
            when (event["type"]?.jsonPrimitive?.content) {
                "response.output_text.delta" -> {
                    val delta = event["delta"]?.jsonPrimitive?.content
                        ?: throw IllegalStateException("OpenAI response delta omitted delta text")
                    decoder.accept(delta).forEach {
                        emittedResponse.append(it)
                        emit(LlmStreamEvent.ResponseDelta(it))
                    }
                }

                "response.completed" -> {
                    check(!completed) { "OpenAI response stream completed more than once" }
                    val output = decoder.finish().copy(
                        cacheMetrics = parseUsage(event["response"]?.jsonObject?.get("usage")),
                    )
                    check(output.response.startsWith(emittedResponse.toString())) {
                        "Streamed response does not match completed structured output"
                    }
                    val remaining = output.response.substring(emittedResponse.length)
                    if (remaining.isNotEmpty()) emit(LlmStreamEvent.ResponseDelta(remaining))
                    emit(LlmStreamEvent.Completed(output))
                    completed = true
                }

                "response.failed", "error" -> throw IllegalStateException("OpenAI response stream failed")
            }
        }

        val channel = response.bodyAsChannel()
        while (!channel.isClosedForRead) {
            val line = channel.readLine() ?: break
            when {
                line.isEmpty() -> consumeFrame()
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.removePrefix("data:").trimStart())
                }
            }
        }
        consumeFrame()
        check(completed) { "OpenAI response stream ended without response.completed" }
    }

    private suspend fun execute(
        prompt: BuiltPrompt,
        generationSettings: LlmGenerationSettings,
        stream: Boolean,
    ): HttpResponse {
        val metadata = cachePolicy.requestMetadata(resolveCapabilities())
        val first = post(prompt, generationSettings, stream, metadata)
        if (first.status.isSuccess()) return first

        val firstError = first.bodyAsText().take(MAX_ERROR_BODY_LENGTH)
        if (isRecognizedUnsupportedCacheField(first.status, firstError, metadata)) {
            return requireSuccessful(
                post(prompt, generationSettings, stream, OpenAiRequestCacheMetadata.None),
            )
        }
        throw providerFailure(first.status, firstError)
    }

    private suspend fun post(
        prompt: BuiltPrompt,
        generationSettings: LlmGenerationSettings,
        stream: Boolean,
        metadata: OpenAiRequestCacheMetadata,
    ): HttpResponse = httpClient.post("${baseUrl.trimEnd('/')}/responses") {
            val cacheBreakpoint = ResponsesPromptCacheBreakpoint("explicit").takeIf { metadata.breakpoint }
            bearerAuth(apiKey)
            contentType(ContentType.Application.Json)
            setBody(
                ResponsesRequest(
                    model = model,
                    promptCacheKey = promptCacheKey(prompt).takeIf { metadata.cacheKey },
                    promptCacheOptions = ResponsesPromptCacheOptions("explicit").takeIf { metadata.cacheOptions },
                    temperature = generationSettings.temperature,
                    maxOutputTokens = generationSettings.maxOutputTokens,
                    reasoning = ResponsesReasoning(reasoningEffort.value),
                    input = buildList {
                        prompt.wireMessages().forEach { message ->
                            add(
                                textMessage(
                                    role = message.role.apiValue,
                                    text = message.content,
                                    breakpoint = cacheBreakpoint.takeIf {
                                        message.segmentKind == PromptSegmentKind.INCARNATION_ANCHOR
                                    },
                                ),
                            )
                        }
                    },
                    text = TextFormat(
                        format = JsonSchemaFormat(
                            type = "json_schema",
                            name = "openeden_llm_output",
                            schema = llmOutputSchema,
                            strict = true,
                        ),
                        verbosity = generationSettings.verbosity.apiValue,
                    ),
                    stream = stream,
                ),
            )
        }

    private suspend fun requireSuccessful(response: HttpResponse): HttpResponse {
        if (response.status.isSuccess()) return response
        val errorBody = response.bodyAsText().take(MAX_ERROR_BODY_LENGTH)
        throw providerFailure(response.status, errorBody)
    }

    private fun providerFailure(status: HttpStatusCode, errorBody: String): IllegalStateException {
        val suffix = if (errorBody.isBlank()) "" else ": $errorBody"
        return IllegalStateException("OpenAI Responses API request failed: ${status.value} ${status.description}$suffix")
    }

    private suspend fun resolveCapabilities(): OpenAiProviderCapabilities {
        if (cachePolicy == OpenAiCachePolicy.OBSERVE_ONLY || cachePolicy == OpenAiCachePolicy.CACHE_DISABLED) {
            return OpenAiProviderCapabilities.unavailable(System.currentTimeMillis())
        }
        return try {
            capabilityProvider.capabilities()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            OpenAiProviderCapabilities.unavailable(System.currentTimeMillis())
        }
    }

    private fun isRecognizedUnsupportedCacheField(
        status: HttpStatusCode,
        errorBody: String,
        metadata: OpenAiRequestCacheMetadata,
    ): Boolean {
        if (!metadata.isPresent || status.value !in RECOGNIZED_UNSUPPORTED_STATUS_CODES) return false
        val normalized = errorBody.lowercase()
        val mentionsSentField =
            (metadata.cacheKey && "prompt_cache_key" in normalized) ||
                (metadata.cacheOptions && "prompt_cache_options" in normalized) ||
                (metadata.breakpoint && "prompt_cache_breakpoint" in normalized)
        return mentionsSentField && UNSUPPORTED_FIELD_MARKERS.any(normalized::contains)
    }

    private fun parseBufferedResponse(bodyText: String): LlmOutput {
        val bodyElement = json.parseToJsonElement(bodyText).jsonObject
        val body = json.decodeFromString<ResponsesResponse>(bodyText)
        val outputText = body.outputText
            ?: body.output.orEmpty().flatMap { it.content.orEmpty() }.firstNotNullOfOrNull { it.text }
            ?: throw IllegalStateException("OpenAI Responses API response did not contain output text")
        val root = json.parseToJsonElement(outputText).jsonObject
        return LlmOutput(
            internalLogic = root.getValue("internal_logic").jsonPrimitive.content,
            vectorDelta = root.getValue("vector_delta").jsonObject.mapValues { (_, value) -> value.jsonPrimitive.float },
            response = root.getValue("response").jsonPrimitive.content,
            cacheMetrics = parseUsage(bodyElement["usage"]),
        )
    }

    private fun parseUsage(element: JsonElement?): LlmCacheMetrics? {
        if (!cachePolicy.observesUsage()) return null
        return runCatching {
            element?.let { json.decodeFromJsonElement<ResponsesUsage>(it).toCacheMetrics() }
        }.getOrNull()
    }

    private fun promptCacheKey(prompt: BuiltPrompt): String {
        val material = buildString {
            listOf(
                cacheKeyContext.providerPolicyRevision,
                model,
                cachePolicy.name,
                cacheKeyContext.systemSchemaRevision,
                cacheKeyContext.personaRevision,
                prompt.cacheIdentity,
                prompt.conversationCacheIdentity.opaqueValue,
                cacheKeyContext.dialogueNamespace,
            ).forEach { value ->
                append(value.length).append(':').append(value)
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun textMessage(
        role: String,
        text: String,
        breakpoint: ResponsesPromptCacheBreakpoint? = null,
    ): ResponsesInputMessage = ResponsesInputMessage(
        role = role,
        content = if (breakpoint == null) {
            JsonPrimitive(text)
        } else {
            JsonArray(
                listOf(
                    JsonObject(
                        buildMap {
                            put("type", JsonPrimitive("input_text"))
                            put("text", JsonPrimitive(text))
                            put("prompt_cache_breakpoint", JsonObject(mapOf("mode" to JsonPrimitive(breakpoint.mode))))
                        },
                    ),
                ),
            )
        },
    )

    override fun close() = httpClient.close()

    companion object {
        private const val MAX_ERROR_BODY_LENGTH = 1_000
        private val RECOGNIZED_UNSUPPORTED_STATUS_CODES = setOf(400, 422)
        private val UNSUPPORTED_FIELD_MARKERS = listOf(
            "unsupported",
            "unknown field",
            "unknown parameter",
            "unrecognized",
            "not permitted",
            "not allowed",
            "extra inputs",
        )

        fun httpClient(engine: HttpClientEngine, installTimeout: Boolean = true): HttpClient = HttpClient(engine) {
            if (installTimeout) install(HttpTimeout) {
                requestTimeoutMillis = 120_000
                connectTimeoutMillis = 30_000
                socketTimeoutMillis = 120_000
            }
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false })
            }
        }
    }
}

@Serializable
private data class ResponsesRequest(
    val model: String,
    @SerialName("prompt_cache_key") val promptCacheKey: String? = null,
    @SerialName("prompt_cache_options") val promptCacheOptions: ResponsesPromptCacheOptions? = null,
    val temperature: Float,
    @SerialName("max_output_tokens") val maxOutputTokens: Int? = null,
    val reasoning: ResponsesReasoning,
    val input: List<ResponsesInputMessage>,
    val text: TextFormat,
    val stream: Boolean = false,
)

@Serializable
private data class ResponsesReasoning(val effort: String)

@Serializable
private data class ResponsesInputMessage(
    val role: String,
    val content: JsonElement,
    val type: String = "message",
)

@Serializable
private data class ResponsesPromptCacheOptions(val mode: String)

private data class ResponsesPromptCacheBreakpoint(val mode: String)

@Serializable
private data class TextFormat(val format: JsonSchemaFormat, val verbosity: String)

@Serializable
private data class JsonSchemaFormat(val type: String, val name: String, val schema: JsonElement, val strict: Boolean)

@Serializable
private data class ResponsesResponse(
    @SerialName("output_text") val outputText: String? = null,
    val output: List<ResponseOutputItem>? = null,
)

@Serializable
private data class ResponsesUsage(
    @SerialName("input_tokens") val inputTokens: Long? = null,
    @SerialName("input_tokens_details") val inputTokensDetails: ResponsesInputTokensDetails? = null,
) {
    fun toCacheMetrics(): LlmCacheMetrics? = inputTokens?.let {
        LlmCacheMetrics(
            inputTokens = it,
            cachedInputTokens = inputTokensDetails?.cachedTokens ?: 0L,
            cacheWriteTokens = inputTokensDetails?.cacheWriteTokens ?: 0L,
            availability = if (inputTokensDetails == null) {
                CacheMetricAvailability.UNOBSERVABLE
            } else {
                CacheMetricAvailability.REPORTED
            },
        )
    }
}

@Serializable
private data class ResponsesInputTokensDetails(
    @SerialName("cached_tokens") val cachedTokens: Long = 0L,
    @SerialName("cache_write_tokens") val cacheWriteTokens: Long = 0L,
)

@Serializable
private data class ResponseOutputItem(val content: List<ResponseContentItem>? = null)

@Serializable
private data class ResponseContentItem(val text: String? = null)

private val llmOutputSchema: JsonElement = JsonObject(
    mapOf(
        "type" to jsonString("object"),
        "additionalProperties" to JsonPrimitive(false),
        "required" to jsonArray("internal_logic", "vector_delta", "response"),
        "properties" to JsonObject(
            mapOf(
                "internal_logic" to JsonObject(mapOf("type" to jsonString("string"))),
                "vector_delta" to JsonObject(
                    mapOf(
                        "type" to jsonString("object"),
                        "additionalProperties" to JsonPrimitive(false),
                        "required" to jsonArray("L", "P", "E", "S", "tau", "V", "M", "F"),
                        "properties" to JsonObject(
                            listOf("L", "P", "E", "S", "tau", "V", "M", "F").associateWith {
                                JsonObject(mapOf("type" to jsonString("number")))
                            },
                        ),
                    ),
                ),
                "response" to JsonObject(mapOf("type" to jsonString("string"))),
            ),
        ),
    ),
)

private fun jsonString(value: String) = JsonPrimitive(value)
private fun jsonArray(vararg values: String) = JsonArray(values.map(::jsonString))

private fun unavailableCapabilityProvider() = OpenAiCapabilityProvider {
    OpenAiProviderCapabilities.unavailable(System.currentTimeMillis())
}
