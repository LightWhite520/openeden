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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

private val log = KtorSimpleLogger("io.openeden.llm.OpenAiResponsesLlmClient")

class OpenAiResponsesLlmClient(
    private val apiKey: String,
    private val model: String,
    private val reasoningEffort: ReasoningEffort = ReasoningEffort.MEDIUM,
    private val baseUrl: String = "https://api.openai.com/v1",
    private val httpClient: HttpClient = httpClient(CIO.create()),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : StreamingLlmClient, AutoCloseable {
    override val supportsStrictStructuredStreaming: Boolean = true

    override suspend fun complete(prompt: BuiltPrompt): LlmOutput {
        log.info("\nPrompt:\n${prompt.systemText}\n${prompt.personaText}\n${prompt.userText}")
        val response = execute(prompt, stream = false)
        requireSuccess(response)
        val llmOutput = parseBufferedResponse(response.bodyAsText())
        return llmOutput
    }

    override fun stream(prompt: BuiltPrompt): Flow<LlmStreamEvent> = flow {
        log.info("\nPrompt:\n${prompt.systemText}\n${prompt.personaText}\n${prompt.userText}")
        val response = execute(prompt, stream = true)
        requireSuccess(response)
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
                    val output = decoder.finish()
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

    private suspend fun execute(prompt: BuiltPrompt, stream: Boolean): HttpResponse =
        httpClient.post("${baseUrl.trimEnd('/')}/responses") {
            bearerAuth(apiKey)
            contentType(ContentType.Application.Json)
            setBody(
                ResponsesRequest(
                    model = model,
                    reasoning = ResponsesReasoning(reasoningEffort.value),
                    input = listOf(
                        ResponsesInputMessage(role = "system", content = prompt.systemText),
                        ResponsesInputMessage(role = "developer", content = prompt.personaText),
                        ResponsesInputMessage(role = "user", content = prompt.userText),
                    ),
                    text = TextFormat(
                        format = JsonSchemaFormat(
                            type = "json_schema",
                            name = "openeden_llm_output",
                            schema = llmOutputSchema,
                            strict = true,
                        ),
                    ),
                    stream = stream,
                ),
            )
        }

    private suspend fun requireSuccess(response: HttpResponse) {
        if (response.status.value.toString().startsWith("2")) return
        val errorBody = response.bodyAsText().take(1000)
        val suffix = if (errorBody.isBlank()) "" else ": $errorBody"
        throw IllegalStateException("OpenAI Responses API request failed: ${response.status.value} ${response.status.description}$suffix")
    }

    private fun parseBufferedResponse(bodyText: String): LlmOutput {
        val body = json.decodeFromString<ResponsesResponse>(bodyText)
        val outputText = body.outputText
            ?: body.output.orEmpty().flatMap { it.content.orEmpty() }.firstNotNullOfOrNull { it.text }
            ?: throw IllegalStateException("OpenAI Responses API response did not contain output text")
        val root = json.parseToJsonElement(outputText).jsonObject
        return LlmOutput(
            internalLogic = root.getValue("internal_logic").jsonPrimitive.content,
            vectorDelta = root.getValue("vector_delta").jsonObject.mapValues { (_, value) -> value.jsonPrimitive.float },
            response = root.getValue("response").jsonPrimitive.content,
        )
    }

    override fun close() = httpClient.close()

    companion object {
        fun httpClient(engine: HttpClientEngine, installTimeout: Boolean = true): HttpClient = HttpClient(engine) {
            if (installTimeout) install(HttpTimeout) {
                requestTimeoutMillis = 120_000
                connectTimeoutMillis = 30_000
                socketTimeoutMillis = 120_000
            }
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
            }
        }
    }
}

@Serializable
private data class ResponsesRequest(
    val model: String,
    val reasoning: ResponsesReasoning,
    val input: List<ResponsesInputMessage>,
    val text: TextFormat,
    val stream: Boolean = false,
)

@Serializable
private data class ResponsesReasoning(val effort: String)

@Serializable
private data class ResponsesInputMessage(val role: String, val content: String)

@Serializable
private data class TextFormat(val format: JsonSchemaFormat)

@Serializable
private data class JsonSchemaFormat(val type: String, val name: String, val schema: JsonElement, val strict: Boolean)

@Serializable
private data class ResponsesResponse(
    @SerialName("output_text") val outputText: String? = null,
    val output: List<ResponseOutputItem>? = null,
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
