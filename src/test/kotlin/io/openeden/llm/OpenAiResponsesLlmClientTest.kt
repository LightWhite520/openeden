package io.openeden.llm

import io.openeden.prompt.BuiltPrompt
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OpenAiResponsesLlmClientTest {
    @Test
    fun `sends prompt layers to responses api and parses output json`() = runTest {
        var requestBody = ""
        var requestUrl = ""
        val engine = MockEngine { request ->
            requestUrl = request.url.toString()
            requestBody = request.body.toByteArray().decodeToString()
            assertEquals("Bearer sk-test", request.headers[HttpHeaders.Authorization])
            respond(
                content = """
                    {
                      "output_text": "{\"internal_logic\":\"logic\",\"vector_delta\":{\"L\":0.0,\"P\":0.1,\"E\":0.0,\"S\":0.0,\"tau\":0.0,\"V\":0.0,\"M\":0.0,\"F\":0.0},\"response\":\"你好\"}"
                    }
                """.trimIndent(),
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = OpenAiResponsesLlmClient(
            apiKey = "sk-test",
            model = "gpt-5-mini",
            baseUrl = "https://relay.example.com/v1",
            httpClient = OpenAiResponsesLlmClient.httpClient(engine, installTimeout = false),
        )

        val output = client.complete(BuiltPrompt("system", "persona", "user"))

        assertEquals("logic", output.internalLogic)
        assertEquals(0.1f, output.vectorDelta.getValue("P"))
        assertEquals("你好", output.response)

        val body = Json.parseToJsonElement(requestBody).jsonObject
        assertEquals("https://relay.example.com/v1/responses", requestUrl)
        assertEquals("gpt-5-mini", body.getValue("model").jsonPrimitive.content)
        val input = body.getValue("input").jsonArray
        assertEquals("system", input[0].jsonObject.getValue("role").jsonPrimitive.content)
        assertEquals("system", input[0].jsonObject.getValue("content").jsonPrimitive.content)
        assertEquals("developer", input[1].jsonObject.getValue("role").jsonPrimitive.content)
        assertEquals("persona", input[1].jsonObject.getValue("content").jsonPrimitive.content)
        assertEquals("user", input[2].jsonObject.getValue("role").jsonPrimitive.content)
        assertEquals("user", input[2].jsonObject.getValue("content").jsonPrimitive.content)
        val format = body.getValue("text").jsonObject.getValue("format").jsonObject
        assertEquals("json_schema", format.getValue("type").jsonPrimitive.content)
        assertEquals("medium", body.getValue("reasoning").jsonObject.getValue("effort").jsonPrimitive.content)
    }

    @Test
    fun `sends configured reasoning effort`() = runTest {
        var requestBody = ""
        val engine = MockEngine { request ->
            requestBody = request.body.toByteArray().decodeToString()
            respond(
                content = "{\"output_text\":\"{\\\"internal_logic\\\":\\\"logic\\\",\\\"vector_delta\\\":{\\\"L\\\":0.0,\\\"P\\\":0.0,\\\"E\\\":0.0,\\\"S\\\":0.0,\\\"tau\\\":0.0,\\\"V\\\":0.0,\\\"M\\\":0.0,\\\"F\\\":0.0},\\\"response\\\":\\\"ok\\\"}\"}",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = OpenAiResponsesLlmClient(
            apiKey = "sk-test",
            model = "gpt-5-mini",
            reasoningEffort = ReasoningEffort.HIGH,
            httpClient = OpenAiResponsesLlmClient.httpClient(engine, installTimeout = false),
        )

        client.complete(BuiltPrompt("system", "persona", "user"))

        val body = Json.parseToJsonElement(requestBody).jsonObject
        assertEquals("high", body.getValue("reasoning").jsonObject.getValue("effort").jsonPrimitive.content)
    }

    @Test
    fun `throws clear error on non successful provider response`() = runTest {
        val engine = MockEngine {
            respondError(HttpStatusCode.Unauthorized, "bad key")
        }
        val client = OpenAiResponsesLlmClient(
            apiKey = "sk-test",
            model = "gpt-5-mini",
            httpClient = OpenAiResponsesLlmClient.httpClient(engine, installTimeout = false),
        )

        val error = assertFailsWith<IllegalStateException> {
            client.complete(BuiltPrompt("system", "persona", "user"))
        }

        assertEquals("OpenAI Responses API request failed: 401 Unauthorized: bad key", error.message)
    }
}
