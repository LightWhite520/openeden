package io.openeden.trace

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TraceContractsTest {
    @Test
    fun `trace sanitizes secrets and bounds diagnostic payloads`() = runTest {
        val store = InMemoryTraceStore()
        store.append(
            TraceSpan(
                context = TraceContext("trace", "turn", "session"),
                spanId = "span",
                stage = "llm",
                status = TraceStatus.OK,
                startedAtMs = 1,
                attributes = mapOf(
                    "api_key" to "secret",
                    "prompt" to "x".repeat(1000),
                    "safe" to "value",
                ),
                errorSummary = "e".repeat(1000),
            ),
        )
        val span = store.snapshot().single()
        assertFalse("api_key" in span.attributes)
        assertEquals(256, span.attributes.getValue("prompt").length)
        assertEquals("value", span.attributes.getValue("safe"))
        assertTrue(span.errorSummary!!.length <= 500)
    }
}
