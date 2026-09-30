package io.openeden.trace


import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TraceContractsTest {
    @Test
    fun `structured lineage is retained while malformed or oversized evidence fails explicitly`() {
        val ids = (1..100).joinToString(",", "[", "]") { "\"turn-$it\"" }
        val span = TraceSpan(context = TraceContext("trace", "turn", "session"), spanId = "span",
            stage = "retrieval", status = TraceStatus.OK, startedAtMs = 0,
            attributes = mapOf("history_source_turn_ids" to ids, "history_sealed_chunks" to "[not json]",
                "retrieved_lineage" to "x".repeat(65_537), "authorization" to "secret"))
        val safe = TraceSanitizer.sanitize(span)
        assertEquals(ids, safe.attributes["history_source_turn_ids"])
        assertTrue(safe.attributes.getValue("history_sealed_chunks").contains("INVALID"))
        assertTrue(safe.attributes.getValue("retrieved_lineage").contains("TOO_LARGE"))
        assertFalse("authorization" in safe.attributes)
    }

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
