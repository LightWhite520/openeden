package io.openeden.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StrictOutputStreamDecoderTest {
    @Test
    fun `emits only decoded response characters across arbitrary chunk boundaries`() {
        val structured = validJson("你\\n好\\\" \\uD83D\\uDC4B")

        for (split in 1 until structured.length) {
            val decoder = StrictOutputStreamDecoder()
            val deltas = decoder.accept(structured.take(split)) + decoder.accept(structured.drop(split))

            assertEquals("你\n好\" 👋", deltas.joinToString(""), "split=$split")
            assertEquals("你\n好\" 👋", decoder.finish().response, "split=$split")
            assertTrue(deltas.none { it.contains("logic") })
        }
    }

    @Test
    fun `rejects response before private fields`() {
        val decoder = StrictOutputStreamDecoder()

        assertEquals(emptyList(), decoder.accept("""{"response":"leak"}"""))
        assertFailsWith<StructuredStreamException> { decoder.finish() }
    }

    @Test
    fun `rejects missing extra and forbidden vector fields`() {
        listOf(
            validJson("ok").replace("\"F\":0.0", "\"D\":0.0"),
            validJson("ok").replace(",\"F\":0.0", ""),
            validJson("ok").replace("\"F\":0.0", "\"F\":0.0,\"X\":0.0"),
        ).forEach { malformed ->
            val decoder = StrictOutputStreamDecoder()
            assertEquals(emptyList(), decoder.accept(malformed))
            assertFailsWith<StructuredStreamException> { decoder.finish() }
        }
    }

    @Test
    fun `does not emit an incomplete unicode surrogate escape`() {
        val prefix = validJson("\\uD83D\\uDC4B").substringBefore("\\uD83D")
        val decoder = StrictOutputStreamDecoder()

        assertEquals(emptyList(), decoder.accept(prefix + "\\uD83D"))
        assertEquals(listOf("👋"), decoder.accept("\\uDC4B\"}"))
    }

    @Test
    fun `accepts schema valid fields in provider order without early response emission`() {
        val decoder = StrictOutputStreamDecoder()
        val structured =
            """{"internal_logic":"private","response":"safe","vector_delta":{"E":0.0,"F":0.0,"L":0.0,"M":0.0,"P":0.0,"S":0.0,"V":0.0,"tau":0.0}}"""

        assertEquals(emptyList(), decoder.accept(structured))
        assertEquals("safe", decoder.finish().response)
    }

    private fun validJson(response: String): String =
        """{"internal_logic":"logic","vector_delta":{"L":0.0,"P":0.1,"E":0.0,"S":0.0,"tau":0.0,"V":0.0,"M":0.0,"F":0.0},"response":"$response"}"""
}
