package io.openeden.relationship

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
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
) : RelationshipEventEvaluator {
    override suspend fun evaluate(turn: RelationshipTurn): RelationshipEvaluation {
        val response = httpClient.post("${baseUrl.trimEnd('/')}/responses") {
            bearerAuth(apiKey)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(requestBody(turn))
        }
        check(response.status.value in 200..299) {
            "OpenAI relationship evaluation failed: ${response.status.value} ${response.bodyAsText().take(1000)}"
        }
        return parseEvaluation(response.bodyAsText(), turn)
    }

    private fun requestBody(turn: RelationshipTurn): JsonObject = buildJsonObject {
        put("model", model)
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
                        put("schema", schema)
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
        val outputText = response["output_text"]?.jsonPrimitive?.content
            ?: error("OpenAI relationship evaluation did not contain output_text")
        val evaluation = json.parseToJsonElement(outputText).jsonObject
        val confidence = evaluation.getValue("confidence").jsonPrimitive.float
        val events = evaluation.getValue("events").jsonArray.map { element ->
            val event = element.jsonObject
            val type = RelationshipEventType.valueOf(event.getValue("type").jsonPrimitive.content)
            RelationshipEvent(
                eventId = "${turn.sourceTurnId}:${type.name}",
                incarnationId = turn.incarnationId,
                canonicalSubjectId = turn.subjectId,
                sourceTurnId = turn.sourceTurnId,
                type = type,
                confidence = confidence,
                evidenceDigest = event.getValue("evidence_digest").jsonPrimitive.content,
                createdAtMs = turn.completedAtMs,
            )
        }
        return RelationshipEvaluation(events, confidence)
    }

    private companion object {
        const val systemInstructions = "Evaluate only relationship events from the validated USER and ATRI turn. Return no response text. Do not infer events from proposals, rhetorical questions, or negations."

        val schema = buildJsonObject {
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
                                                put("enum", JsonArray(RelationshipEventType.entries.map { JsonPrimitive(it.name) }))
                                            })
                                            put("evidence_digest", buildJsonObject { put("type", "string") })
                                        },
                                    )
                                    put("required", JsonArray(listOf(JsonPrimitive("type"), JsonPrimitive("evidence_digest"))))
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
