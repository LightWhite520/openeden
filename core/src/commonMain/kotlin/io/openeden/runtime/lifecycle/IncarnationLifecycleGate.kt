package io.openeden.runtime.lifecycle

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class IncarnationUnavailableException : IllegalStateException("ATRI incarnation is unavailable")

class IncarnationLifecycleGate {
    private val mutex = Mutex()
    private var currentStatus = IncarnationStatus.ACTIVE
    private var activeTurns = 0
    private var drained = CompletableDeferred<Unit>().also { it.complete(Unit) }

    suspend fun status(): IncarnationStatus = mutex.withLock { currentStatus }

    suspend fun <T> withActiveTurn(block: suspend () -> T): T {
        mutex.withLock {
            checkActive()
            if (activeTurns == 0) drained = CompletableDeferred()
            activeTurns += 1
        }
        try {
            return block()
        } finally {
            mutex.withLock {
                activeTurns -= 1
                check(activeTurns >= 0) { "Incarnation active turn count underflow" }
                if (activeTurns == 0) drained.complete(Unit)
            }
        }
    }

    suspend fun beginTermination() {
        val drain = mutex.withLock {
            when (currentStatus) {
                IncarnationStatus.ACTIVE -> {
                    currentStatus = IncarnationStatus.TERMINATING
                    drained
                }
                IncarnationStatus.TERMINATING -> drained
                IncarnationStatus.TERMINATED -> throw IncarnationUnavailableException()
            }
        }
        drain.await()
    }

    suspend fun markTerminated() = mutex.withLock {
        check(currentStatus == IncarnationStatus.TERMINATING) {
            "Incarnation must be terminating before it can be marked terminated"
        }
        check(activeTurns == 0) { "Incarnation still has active turns" }
        currentStatus = IncarnationStatus.TERMINATED
    }

    suspend fun beginFreshIncarnation() = mutex.withLock {
        check(currentStatus == IncarnationStatus.TERMINATED) {
            "A fresh incarnation requires a terminated lifecycle"
        }
        currentStatus = IncarnationStatus.ACTIVE
        activeTurns = 0
        drained = CompletableDeferred<Unit>().also { it.complete(Unit) }
    }

    private fun checkActive() {
        if (currentStatus != IncarnationStatus.ACTIVE) {
            throw IncarnationUnavailableException()
        }
    }
}
