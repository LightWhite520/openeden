package io.openeden.prompt

import io.openeden.runtime.time.MutableRuntimeClock
import io.openeden.runtime.time.TemporalContextProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PromptTimeTest {
    @Test
    fun `ordinary adjacent turn omits exact timestamp`() {
        val clock = MutableRuntimeClock(1_777_000_000_000L)
        val context = TemporalContextProvider(clock).forTurn("今天吃什么", clock.nowMs() - 60_000L)

        assertNull(context.exactTime)
        assertEquals("recent", context.elapsedBucket)
    }

    @Test
    fun `direct time question receives exact authoritative time`() {
        val context = TemporalContextProvider(MutableRuntimeClock(1234L)).forTurn("现在几点", null)

        assertEquals(1234L, context.exactTime)
    }

    @Test
    fun `ordinary statement mentioning time omits exact timestamp`() {
        val context = TemporalContextProvider(MutableRuntimeClock(1234L)).forTurn("我没有时间", null)

        assertNull(context.exactTime)
    }

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
