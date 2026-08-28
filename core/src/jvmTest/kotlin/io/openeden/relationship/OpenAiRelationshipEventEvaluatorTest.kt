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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

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
