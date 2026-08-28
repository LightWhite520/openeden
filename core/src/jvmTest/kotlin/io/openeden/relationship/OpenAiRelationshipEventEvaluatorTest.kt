package io.openeden.relationship

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenAiRelationshipEventEvaluatorTest {
    @Test
    fun `requests strict relationship schema without response field`() = runTest {
        var requestBody = ""
        val evaluator = evaluatorFor { request ->
            requestBody = request.body.toByteArray().decodeToString()
            respond(
                content = """
                    {"output_text":"{\"confidence\":0.9,\"events\":[{\"type\":\"REPAIR\",\"evidence_digest\":\"exact repair\"}]}"}
                """.trimIndent(),
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        val result = evaluator.evaluate(turn())

        assertEquals(RelationshipEventType.REPAIR, result.events.single().type)
        val body = Json.parseToJsonElement(requestBody).jsonObject
        val format = body.getValue("text").jsonObject.getValue("format").jsonObject
        assertEquals(true, format.getValue("strict").jsonPrimitive.boolean)
        val schema = format.getValue("schema").jsonObject
        assertEquals(false, schema.getValue("additionalProperties").jsonPrimitive.boolean)
        val eventSchema = schema.getValue("properties").jsonObject
            .getValue("events").jsonObject.getValue("items").jsonObject
        assertEquals(false, eventSchema.getValue("additionalProperties").jsonPrimitive.boolean)
        assertFalse(eventSchema.getValue("properties").jsonObject.containsKey("response"))
        assertFalse(schema.getValue("properties").jsonObject.containsKey("response"))
        assertFalse(body.toString().contains("internal_logic"))
        assertEquals(512, body.getValue("max_output_tokens").jsonPrimitive.int)
        val eventTypes = eventSchema.getValue("properties").jsonObject
            .getValue("type").jsonObject.getValue("enum").jsonArray
            .map { it.jsonPrimitive.content }
        assertFalse(RelationshipEventType.RESET.name in eventTypes)
    }

    @Test
    fun `parses standard Responses output content text payload`() = runTest {
        val evaluator = evaluatorFor {
            respond(
                content = """
                    {
                      "output": [{
                        "type": "message",
                        "content": [{
                          "type": "output_text",
                          "text": "{\"confidence\":0.91,\"events\":[{\"type\":\"REPAIR\",\"evidence_digest\":\"exact repair\"}] }"
                        }]
                      }]
                    }
                """.trimIndent(),
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        val result = evaluator.evaluate(turn())

        assertEquals(0.91f, result.confidence)
        assertEquals(RelationshipEventType.REPAIR, result.events.single().type)
    }

    @Test
    fun `ordinary model evaluation rejects administrative reset and correction output`() = runTest {
        val resetEvaluator = evaluatorFor {
            respond(
                content = """{"output_text":"{\"confidence\":0.9,\"events\":[{\"type\":\"RESET\",\"evidence_digest\":\"reset\"}]}"}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val correctionEvaluator = evaluatorFor {
            respond(
                content = """{"output_text":"{\"confidence\":0.9,\"events\":[{\"type\":\"REPAIR\",\"evidence_digest\":\"correction\",\"supersedes_event_id\":\"event-1\"}]}"}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        assertFailsWith<IllegalArgumentException> { resetEvaluator.evaluate(turn()) }
        val correctionFailure = assertFailsWith<IllegalArgumentException> { correctionEvaluator.evaluate(turn()) }
        assertTrue(correctionFailure.message.orEmpty().contains("administrative", ignoreCase = true))
    }

    @Test
    fun `rejects malformed relationship provider output`() = runTest {
        val evaluator = evaluatorFor {
            respond(
                content = """{"output_text":"{\"confidence\":0.9,\"events\":[{\"type\":\"REPAIR\"}]}"}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        assertFailsWith<NoSuchElementException> { evaluator.evaluate(turn()) }
    }

    @Test
    fun `rejects unexpected relationship evaluation root fields`() = runTest {
        val evaluator = evaluatorFor {
            respond(
                content = """{"output_text":"{\"confidence\":0.9,\"events\":[],\"response\":\"not allowed\"}"}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        val failure = assertFailsWith<IllegalArgumentException> { evaluator.evaluate(turn()) }

        assertTrue(failure.message.orEmpty().contains("root", ignoreCase = true))
    }

    @Test
    fun `rejects oversized provider response before parsing`() = runTest {
        val evaluator = evaluatorFor {
            respond(
                content = "x".repeat(64 * 1024 + 1),
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        val failure = assertFailsWith<IllegalStateException> { evaluator.evaluate(turn()) }

        assertTrue(failure.message.orEmpty().contains("exceeded limit"))
    }

    private fun evaluatorFor(handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData): OpenAiRelationshipEventEvaluator =
        OpenAiRelationshipEventEvaluator(
            apiKey = "sk-test",
            model = "test-model",
            baseUrl = "https://relay.example.test/v1",
            httpClient = HttpClient(MockEngine(handler)) {
                install(ContentNegotiation) { json() }
            },
        )

    private fun turn() = RelationshipTurn(
        sourceTurnId = "turn-1",
        incarnationId = "incarnation-1",
        subjectId = "QQ:user-1",
        userText = "对不起，我刚才弄错了",
        assistantText = "我听到了。",
        completedAtMs = 1L,
    )
}
