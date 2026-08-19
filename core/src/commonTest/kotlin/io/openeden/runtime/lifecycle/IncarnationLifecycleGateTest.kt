package io.openeden.runtime.lifecycle

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class IncarnationLifecycleGateTest {
    @Test
    fun `termination waits for admitted turns and rejects new turns`() = runTest {
        val gate = IncarnationLifecycleGate()
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val turn = launch {
            gate.withActiveTurn {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()

        val terminating = async { gate.beginTermination() }
        yield()
        assertFalse(terminating.isCompleted)
        assertFailsWith<IncarnationUnavailableException> { gate.withActiveTurn { } }

        release.complete(Unit)
        terminating.await()
        turn.join()
        assertEquals(IncarnationStatus.TERMINATING, gate.status())
    }

    @Test
    fun `terminated gate rejects turns and only explicit activation reopens it`() = runTest {
        val gate = IncarnationLifecycleGate()
        gate.beginTermination()
        gate.markTerminated()

        assertFailsWith<IncarnationUnavailableException> { gate.withActiveTurn { } }
        gate.beginFreshIncarnation()
        assertEquals(IncarnationStatus.ACTIVE, gate.status())
        assertEquals("ok", gate.withActiveTurn { "ok" })
    }
}
