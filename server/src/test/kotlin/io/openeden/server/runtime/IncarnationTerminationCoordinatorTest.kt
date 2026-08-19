package io.openeden.server.runtime

import io.openeden.runtime.lifecycle.IncarnationLifecycle
import io.openeden.runtime.lifecycle.IncarnationLifecycleGate
import io.openeden.runtime.lifecycle.IncarnationLifecycleStore
import io.openeden.runtime.lifecycle.IncarnationStatus
import io.openeden.runtime.lifecycle.IncarnationTerminationStore
import io.openeden.runtime.lifecycle.TerminationReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class IncarnationTerminationCoordinatorTest {
    @Test
    fun `termination drains admitted turns before archive and requires explicit fresh creation`() = runTest {
        val gate = IncarnationLifecycleGate()
        val store = FakeTerminationStore()
        val coordinator = IncarnationTerminationCoordinator(gate, store)
        val release = CompletableDeferred<Unit>()
        val admitted = launch { gate.withActiveTurn { release.await() } }
        val terminating = async {
            coordinator.terminate(TerminationReason("critical", 100L))
        }

        yield()
        assertFalse(terminating.isCompleted)
        assertFalse(store.archiveCalled)
        release.complete(Unit)
        terminating.await()
        admitted.join()

        assertEquals(IncarnationStatus.TERMINATED, gate.status())
        assertEquals(IncarnationLifecycle.TERMINATED, store.status)
        assertEquals(true, store.archiveCalled)
        val id = coordinator.createFreshIncarnation("fresh", 200L)
        assertEquals("fresh-id", id)
        assertEquals(IncarnationStatus.ACTIVE, gate.status())
    }

    @Test
    fun `archive failure leaves persistent and admission lifecycle terminating`() = runTest {
        val gate = IncarnationLifecycleGate()
        val store = FakeTerminationStore(archiveFailure = true)
        val coordinator = IncarnationTerminationCoordinator(gate, store)

        assertFailsWith<IllegalStateException> {
            coordinator.terminate(TerminationReason("critical", 100L))
        }

        assertEquals(IncarnationStatus.TERMINATING, gate.status())
        assertEquals(IncarnationLifecycle.TERMINATING, store.status)
        assertFailsWith<IllegalStateException> { gate.withActiveTurn { } }
    }
}

private class FakeTerminationStore(
    private val archiveFailure: Boolean = false,
) : IncarnationTerminationStore {
    var status = IncarnationLifecycle.ACTIVE
    var archiveCalled = false

    override suspend fun read(): IncarnationLifecycle = status

    override suspend fun markCritical(): IncarnationLifecycle {
        if (status == IncarnationLifecycle.ACTIVE) status = IncarnationLifecycle.CRITICAL
        return status
    }

    override suspend fun beginTermination(): IncarnationLifecycle {
        check(status == IncarnationLifecycle.CRITICAL)
        status = IncarnationLifecycle.TERMINATING
        return status
    }

    override suspend fun markTerminated(): IncarnationLifecycle {
        check(status == IncarnationLifecycle.TERMINATING)
        status = IncarnationLifecycle.TERMINATED
        return status
    }

    override suspend fun createFresh(requestId: String, nowMs: Long): String {
        check(status == IncarnationLifecycle.TERMINATED)
        status = IncarnationLifecycle.ACTIVE
        return "fresh-id"
    }

    override suspend fun archiveAndPurge(reason: TerminationReason) {
        archiveCalled = true
        if (archiveFailure) error("archive failed")
        check(status == IncarnationLifecycle.TERMINATING)
        status = IncarnationLifecycle.TERMINATED
    }
}
