package io.openeden.llm

import io.openeden.prompt.BuiltPrompt
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class OpenAiResponsesLlmClient(
    private val apiKey: String,
    private val model: String,
    private val baseUrl: String = "https://api.openai.com/v1",
    private val httpClient: HttpClient = httpClient(CIO.create()),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : io.openeden.llm.LlmClient {
    override suspend fun complete(prompt: BuiltPrompt): io.openeden.llm.LlmOutput {
        val response = httpClient.post("${baseUrl.trimEnd('/')}/responses") {
            bearerAuth(apiKey)
            contentType(ContentType.Application.Json)
            setBody(
                ResponsesRequest(
                    model = model,
                    input = listOf(prompt.systemText, prompt.personaText, prompt.userText).joinToString("\n\n"),
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
            ?: extractOutputText(body.output)
            ?: throw IllegalStateException("OpenAI Responses API response did not contain output text")
        return parseOutput(outputText)
    }

    private fun parseOutput(outputText: String): io.openeden.llm.LlmOutput {
        val root = json.parseToJsonElement(outputText).jsonObject
        val delta = root.getValue("vector_delta").jsonObject.mapValues { (_, value) ->
            value.jsonPrimitive.float
        }
        return io.openeden.llm.LlmOutput(
            internalLogic = root.getValue("internal_logic").jsonPrimitive.content,
            vectorDelta = delta,
            response = root.getValue("response").jsonPrimitive.content,
        )
    }

    private fun extractOutputText(output: List<ResponseOutputItem>?): String? =
        output.orEmpty()
            .flatMap { it.content.orEmpty() }
            .firstNotNullOfOrNull { it.text }

    companion object {
        fun httpClient(engine: HttpClientEngine, installTimeout: Boolean = true): HttpClient = HttpClient(engine) {
            if (installTimeout) {
                install(HttpTimeout) {
                    requestTimeoutMillis = 120_000
                    connectTimeoutMillis = 30_000
                    socketTimeoutMillis = 120_000
                }
            }
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                })
            }
        }
    }
}

@Serializable
private data class ResponsesRequest(
    val model: String,
    val input: String,
    val text: TextFormat,
)

@Serializable
private data class TextFormat(
    val format: JsonSchemaFormat,
)

@Serializable
private data class JsonSchemaFormat(
    val type: String,
    val name: String,
    val schema: JsonElement,
    val strict: Boolean,
)

@Serializable
private data class ResponsesResponse(
    @SerialName("output_text")
    val outputText: String? = null,
    val output: List<ResponseOutputItem>? = null,
)

@Serializable
private data class ResponseOutputItem(
    val content: List<ResponseContentItem>? = null,
)

@Serializable
private data class ResponseContentItem(
    val text: String? = null,
)

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

private fun jsonString(value: String): kotlinx.serialization.json.JsonPrimitive =
    kotlinx.serialization.json.JsonPrimitive(value)

private fun jsonArray(vararg values: String): kotlinx.serialization.json.JsonArray =
    kotlinx.serialization.json.JsonArray(values.map(::jsonString))
