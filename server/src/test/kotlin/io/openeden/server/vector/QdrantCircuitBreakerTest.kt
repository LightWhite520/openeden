package io.openeden.server.vector

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class QdrantCircuitBreakerTest {
    @Test
    fun `three failures open and open circuit bypasses operation`() = runTest {
        var now = 0L
        val breaker = QdrantCircuitBreaker(failureThreshold = 3, probeIntervalMs = 100, nowMs = { now })
        repeat(3) { assertFailsWith<IllegalStateException> { breaker.execute { error("down") } } }

        assertEquals(QdrantCircuitBreaker.State.OPEN, breaker.snapshot().state)
        assertNull(breaker.execute { "must not run" })
    }

    @Test
    fun `half open success closes only after operation succeeds`() = runTest {
        var now = 0L
        val breaker = QdrantCircuitBreaker(failureThreshold = 3, probeIntervalMs = 100, nowMs = { now })
        repeat(3) { assertFailsWith<IllegalStateException> { breaker.execute { error("down") } } }
        now = 100
        assertEquals("ok", breaker.execute { "ok" })
        assertEquals(QdrantCircuitBreaker.State.CLOSED, breaker.snapshot().state)
    }

    @Test
    fun `failed half open probe reopens circuit`() = runTest {
        var now = 0L
        val breaker = QdrantCircuitBreaker(failureThreshold = 3, probeIntervalMs = 100, nowMs = { now })
        repeat(3) { assertFailsWith<IllegalStateException> { breaker.execute { error("down") } } }
        now = 100
        assertFailsWith<IllegalStateException> { breaker.execute { error("still down") } }
        assertEquals(QdrantCircuitBreaker.State.OPEN, breaker.snapshot().state)
        assertNull(breaker.execute { "must not run" })
    }

    @Test
    fun `cancellation propagates without opening or counting as failure`() = runTest {
        val breaker = QdrantCircuitBreaker(failureThreshold = 1, nowMs = { 0L })
        assertFailsWith<CancellationException> { breaker.execute { throw CancellationException("cancel") } }
        assertEquals(QdrantCircuitBreaker.State.CLOSED, breaker.snapshot().state)
        assertEquals("ok", breaker.execute { "ok" })
    }
}
