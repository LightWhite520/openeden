package io.openeden.server.runtime

import io.openeden.runtime.lifecycle.IncarnationLifecycle
import io.openeden.runtime.lifecycle.IncarnationLifecycleGate
import io.openeden.runtime.lifecycle.IncarnationStatus
import io.openeden.runtime.lifecycle.IncarnationTerminationStore
import io.openeden.runtime.lifecycle.TerminationReason
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class IncarnationTerminationCoordinator(
    private val gate: IncarnationLifecycleGate,
    private val store: IncarnationTerminationStore,
    private val runtimeJobs: List<Job> = emptyList(),
) {
    private val mutex = Mutex()

    suspend fun terminate(reason: TerminationReason) = mutex.withLock {
        when (store.read()) {
            IncarnationLifecycle.ACTIVE -> store.markCritical()
            IncarnationLifecycle.CRITICAL -> Unit
            IncarnationLifecycle.TERMINATING -> Unit
            IncarnationLifecycle.TERMINATED -> {
                if (gate.status() == IncarnationStatus.ACTIVE) {
                    gate.beginTermination()
                    gate.markTerminated()
                }
                return@withLock
            }
        }
        when (store.read()) {
            IncarnationLifecycle.CRITICAL -> store.beginTermination()
            IncarnationLifecycle.TERMINATING -> Unit
            else -> error("Incarnation changed while termination was being prepared")
        }
        gate.beginTermination()
        runtimeJobs.forEach { it.cancelAndJoin() }
        store.archiveAndPurge(reason)
        gate.markTerminated()
    }

    suspend fun createFreshIncarnation(requestId: String, nowMs: Long): String = mutex.withLock {
        check(store.read() == IncarnationLifecycle.TERMINATED) {
            "Fresh incarnation requires TERMINATED lifecycle"
        }
        val id = store.createFresh(requestId, nowMs)
        gate.beginFreshIncarnation()
        id
    }
}
