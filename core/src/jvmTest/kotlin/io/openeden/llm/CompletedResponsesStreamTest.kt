package io.openeden.llm

import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class CompletedResponsesStreamTest {
    @Test
    fun `collects streamed text when completed envelope omits output`() = runTest {
        val result = collect(
            """{"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"{\"confidence\":"}""",
            """{"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"0.95,\"events\":[]}"}""",
            """{"type":"response.output_text.done","output_index":0,"content_index":0,"text":"{\"confidence\":0.95,\"events\":[]}"}""",
            """{"type":"response.completed","response":{"status":"completed","model":"test-model","output":[]}}""",
        )
        assertEquals("test-model", result.getValue("model").jsonPrimitive.content)
        assertEquals("""{"confidence":0.95,"events":[]}""", result.getValue("output_text").jsonPrimitive.content)
    }

    @Test
    fun `orders multiple content parts and accepts done without deltas`() = runTest {
        val result = collect(
            """{"type":"response.output_text.done","output_index":1,"content_index":0,"text":"world"}""",
            """{"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"hello "}""",
            """{"type":"response.completed","response":{"output":[]}}""",
        )
        assertEquals("hello world", result.getValue("output_text").jsonPrimitive.content)
    }

    @Test
    fun `keeps populated completed output without duplicating streamed text`() = runTest {
        val result = collect(
            """{"type":"response.output_text.delta","delta":"hello"}""",
            """{"type":"response.completed","response":{"output":[{"content":[{"type":"output_text","text":"hello"}]}]}}""",
        )
        assertFalse("output_text" in result)
        assertEquals(1, result.getValue("output").jsonArray.size)
    }

    @Test
    fun `partial text never replaces successful stream completion`() = runTest {
        for (event in listOf("response.failed", "response.incomplete", "error")) {
            assertFailsWith<IllegalStateException> {
                collect("""{"type":"response.output_text.delta","delta":"{}"}""", """{"type":"$event"}""")
            }
        }
        assertFailsWith<IllegalStateException> { collect("""{"type":"response.output_text.delta","delta":"{}"}""") }
    }

    @Test
    fun `terminal diagnostics expose only allowlisted codes`() = runTest {
        val known = assertFailsWith<ResponsesStreamFailure> {
            collect("""{"type":"response.failed","response":{"error":{"code":"rate_limit_exceeded","message":"private-token"}}}""")
        }
        assertEquals("response.failed", known.terminalEvent)
        assertEquals("rate_limit_exceeded", known.providerCode)
        assertFalse(known.toString().contains("private-token"))
        val unknown = assertFailsWith<ResponsesStreamFailure> {
            collect("""{"type":"error","code":"private-token","message":"private-account"}""")
        }
        assertEquals("OTHER", unknown.providerCode)
        assertFalse(unknown.toString().contains("private"))
        val incomplete = assertFailsWith<ResponsesStreamFailure> {
            collect("""{"type":"response.incomplete","response":{"incomplete_details":{"reason":"max_output_tokens"}}}""")
        }
        assertEquals("max_output_tokens", incomplete.providerCode)
        val quota = assertFailsWith<ResponsesStreamFailure> {
            collect("""{"type":"error","error":{"type":"invalid_request_error","code":"subscription_sharing_usage_limit_exceeded","message":"private details"}}""")
        }
        assertEquals("subscription_sharing_usage_limit_exceeded", quota.providerCode)
    }

    private suspend fun collect(vararg frames: String): JsonObject = Json.parseToJsonElement(
        completedResponsesStream(ByteReadChannel(frames.joinToString("") { "data: $it\n\n" })),
    ).jsonObject
}
