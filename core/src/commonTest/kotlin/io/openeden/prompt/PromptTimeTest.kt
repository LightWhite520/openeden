package io.openeden.prompt

import kotlin.test.Test
import kotlin.test.assertEquals

class PromptTimeTest {
    @Test
    fun `formats epoch milliseconds in Shanghai timezone`() {
        assertEquals(
            "2026-08-23 00:05",
            PromptTime.format(1_787_414_712_000L),
        )
    }

    @Test
    fun `omits seconds from prompt time`() {
        assertEquals("2026-08-22 23:59", PromptTime.format(1_787_414_399_000L))
    }
}
