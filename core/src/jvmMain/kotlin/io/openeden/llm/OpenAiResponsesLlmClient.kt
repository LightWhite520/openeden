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
import io.openeden.prompt.BuiltPrompt
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

import io.ktor.util.logging.KtorSimpleLogger

private val log = KtorSimpleLogger("io.openeden.llm.OpenAiResponsesLlmClient")

class OpenAiResponsesLlmClient(
    private val apiKey: String,
    private val model: String,
    private val reasoningEffort: ReasoningEffort = ReasoningEffort.MEDIUM,
    private val baseUrl: String = "https://api.openai.com/v1",
    private val httpClient: HttpClient = httpClient(CIO.create()),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : LlmClient {
    override suspend fun complete(prompt: BuiltPrompt): LlmOutput {
        log.info("Prompt:\n${prompt.systemText}\n${prompt.personaText}\n${prompt.userText}")
        val response = httpClient.post("${baseUrl.trimEnd('/')}/responses") {
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
                ),
            )
        }
        if (!response.status.value.toString().startsWith("2")) {
            val errorBody = response.bodyAsText().take(1000)
            val suffix = if (errorBody.isBlank()) "" else ": $errorBody"
            throw IllegalStateException("OpenAI Responses API request failed: ${response.status.value} ${response.status.description}$suffix")
        }
        val body = json.decodeFromString<ResponsesResponse>(response.bodyAsText())
        val outputText = body.outputText
            ?: body.output.orEmpty().flatMap { it.content.orEmpty() }.firstNotNullOfOrNull { it.text }
            ?: throw IllegalStateException("OpenAI Responses API response did not contain output text")
        val root = json.parseToJsonElement(outputText).jsonObject
        val llmOutput = LlmOutput(
            internalLogic = root.getValue("internal_logic").jsonPrimitive.content,
            vectorDelta = root.getValue("vector_delta").jsonObject.mapValues { (_, value) -> value.jsonPrimitive.float },
            response = root.getValue("response").jsonPrimitive.content,
        )
        log.info("\n$llmOutput")
        return llmOutput
    }

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

enum class ReasoningEffort(val value: String) {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high");

    companion object {
        fun parse(value: String): ReasoningEffort = entries.firstOrNull { it.value == value.trim().lowercase() }
            ?: error("Unsupported reasoning effort '$value'; expected low, medium, or high")
    }
}

@Serializable
private data class ResponsesRequest(
    val model: String,
    val reasoning: ResponsesReasoning,
    val input: List<ResponsesInputMessage>,
    val text: TextFormat,
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
        "additionalProperties" to kotlinx.serialization.json.JsonPrimitive(false),
        "required" to jsonArray("internal_logic", "vector_delta", "response"),
        "properties" to JsonObject(
            mapOf(
                "internal_logic" to JsonObject(mapOf("type" to jsonString("string"))),
                "response" to JsonObject(mapOf("type" to jsonString("string"))),
                "vector_delta" to JsonObject(
                    mapOf(
                        "type" to jsonString("object"),
                        "additionalProperties" to kotlinx.serialization.json.JsonPrimitive(false),
                        "required" to jsonArray("L", "P", "E", "S", "tau", "V", "M", "F"),
                        "properties" to JsonObject(
                            listOf("L", "P", "E", "S", "tau", "V", "M", "F").associateWith {
                                JsonObject(mapOf("type" to jsonString("number")))
                            },
                        ),
                    ),
                ),
            ),
        ),
    ),
)

private fun jsonString(value: String) = kotlinx.serialization.json.JsonPrimitive(value)
private fun jsonArray(vararg values: String) = kotlinx.serialization.json.JsonArray(values.map(::jsonString))
