package io.openeden.relationship

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.core.readBytes
import io.ktor.utils.io.readRemaining
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Optional strict-JSON evaluator for providers that expose the OpenAI Responses API. */
class OpenAiRelationshipEventEvaluator(
    private val apiKey: String,
    private val model: String,
    private val baseUrl: String,
    private val httpClient: HttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val administrativeEventsAuthorized: Boolean = false,
) : RelationshipEventEvaluator {
    override suspend fun evaluate(turn: RelationshipTurn): RelationshipEvaluation {
        val response = httpClient.post("${baseUrl.trimEnd('/')}/responses") {
            bearerAuth(apiKey)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(requestBody(turn))
        }
        val body = boundedBody(response.bodyAsChannel())
        check(response.status.value in 200..299) {
            "OpenAI relationship evaluation failed: ${response.status.value} ${body.take(1000)}"
        }
        return parseEvaluation(body, turn)
    }

    private fun requestBody(turn: RelationshipTurn): JsonObject = buildJsonObject {
        put("model", model)
        put("max_output_tokens", MaxOutputTokens)
        put(
            "input",
            buildJsonArray {
                add(message("system", systemInstructions))
                add(message("user", "USER:\n${turn.userText}\n\nATRI:\n${turn.assistantText}"))
            },
        )
        put(
            "text",
            buildJsonObject {
                put(
                    "format",
                    buildJsonObject {
                        put("type", "json_schema")
                        put("name", "relationship_evaluation")
                        put("strict", true)
                        put("schema", schema(administrativeEventsAuthorized))
                    },
                )
            },
        )
    }

    private fun message(role: String, text: String): JsonObject = buildJsonObject {
        put("role", role)
        put("content", text)
    }

    private fun parseEvaluation(body: String, turn: RelationshipTurn): RelationshipEvaluation {
        val response = json.parseToJsonElement(body).jsonObject
        val outputText = response.outputText()
        val evaluation = json.parseToJsonElement(outputText).jsonObject
        val unexpectedRootFields = evaluation.keys - evaluationFields
        require(unexpectedRootFields.isEmpty()) {
            "Unexpected relationship evaluation root fields: ${unexpectedRootFields.sorted()}"
        }
        val confidence = evaluation.getValue("confidence").jsonPrimitive.float
        val events = evaluation.getValue("events").jsonArray.map { element ->
            val event = element.jsonObject
            val unexpectedFields = event.keys - eventFields(administrativeEventsAuthorized)
            require(unexpectedFields.isEmpty()) {
                if ("supersedes_event_id" in unexpectedFields) {
                    "Administrative relationship corrections require explicit authorization"
                } else {
                    "Unexpected relationship event fields: ${unexpectedFields.sorted()}"
                }
            }
            val type = RelationshipEventType.valueOf(event.getValue("type").jsonPrimitive.content)
            require(administrativeEventsAuthorized || type != RelationshipEventType.RESET) {
                "Administrative relationship reset requires explicit authorization"
            }
            RelationshipEvent(
                eventId = "${turn.sourceTurnId}:${type.name}",
                incarnationId = turn.incarnationId,
                canonicalSubjectId = turn.subjectId,
                sourceTurnId = turn.sourceTurnId,
                type = type,
                confidence = confidence,
                evidenceDigest = event.getValue("evidence_digest").jsonPrimitive.content,
                createdAtMs = turn.completedAtMs,
                supersedesEventId = event["supersedes_event_id"]?.jsonPrimitive?.contentOrNull,
            )
        }
        return RelationshipEvaluation(events, confidence)
    }

    private suspend fun boundedBody(channel: io.ktor.utils.io.ByteReadChannel): String {
        val bytes = channel.readRemaining(MaxResponseBodyBytes.toLong() + 1L).readBytes()
        check(bytes.size <= MaxResponseBodyBytes) { "OpenAI relationship evaluation response exceeded limit" }
        return bytes.decodeToString()
    }

    private fun JsonObject.outputText(): String {
        this["output_text"]?.jsonPrimitive?.contentOrNull?.let { return it }
        return this["output"]?.jsonArray
            ?.asSequence()
            ?.flatMap { output -> output.jsonObject["content"]?.jsonArray?.asSequence() ?: emptySequence() }
            ?.map { it.jsonObject }
            ?.firstNotNullOfOrNull { content ->
                content["text"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { content["type"]?.jsonPrimitive?.contentOrNull == "output_text" }
            }
            ?: error("OpenAI relationship evaluation did not contain output text")
    }

    private companion object {
        const val MaxOutputTokens = 512
        const val MaxResponseBodyBytes = 64 * 1024
        const val systemInstructions = "Evaluate only ordinary relationship events from the validated USER and ATRI turn. Return no response text. Do not infer events from proposals, rhetorical questions, or negations. Administrative reset and correction events are forbidden unless explicitly authorized by the caller."

        val evaluationFields = setOf("confidence", "events")

        fun eventFields(administrativeEventsAuthorized: Boolean): Set<String> = buildSet {
            add("type")
            add("evidence_digest")
            if (administrativeEventsAuthorized) add("supersedes_event_id")
        }

        fun schema(administrativeEventsAuthorized: Boolean) = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put("confidence", buildJsonObject { put("type", "number") })
                    put(
                        "events",
                        buildJsonObject {
                            put("type", "array")
                            put(
                                "items",
                                buildJsonObject {
                                    put("type", "object")
                                    put(
                                        "properties",
                                        buildJsonObject {
                                            put("type", buildJsonObject {
                                                put("type", "string")
                                                put(
                                                    "enum",
                                                    JsonArray(
                                                        RelationshipEventType.entries
                                                            .filter { administrativeEventsAuthorized || it != RelationshipEventType.RESET }
                                                            .map { JsonPrimitive(it.name) },
                                                    ),
                                                )
                                            })
                                            put("evidence_digest", buildJsonObject { put("type", "string") })
                                            if (administrativeEventsAuthorized) {
                                                put("supersedes_event_id", buildJsonObject {
                                                    put("type", JsonArray(listOf(JsonPrimitive("string"), JsonPrimitive("null"))))
                                                })
                                            }
                                        },
                                    )
                                    put("required", JsonArray(eventFields(administrativeEventsAuthorized).map(::JsonPrimitive)))
                                    put("additionalProperties", false)
                                },
                            )
                        },
                    )
                },
            )
            put("required", JsonArray(listOf(JsonPrimitive("confidence"), JsonPrimitive("events"))))
            put("additionalProperties", false)
        }
    }
}
