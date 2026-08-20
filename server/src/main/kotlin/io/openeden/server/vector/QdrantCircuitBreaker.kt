package io.openeden.server.vector

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Small, single-process circuit breaker for Qdrant availability. */
class QdrantCircuitBreaker(
    private val failureThreshold: Int = 3,
    private val probeIntervalMs: Long = 30_000L,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    enum class State { CLOSED, OPEN, HALF_OPEN }

    data class Snapshot(
        val state: State,
        val consecutiveFailures: Int,
        val openedAtMs: Long?,
        val lastSuccessAtMs: Long?,
        val lastFailure: Throwable?,
    )

    private val mutex = Mutex()
    private var currentState = State.CLOSED
    private var consecutiveFailures = 0
    private var openedAt: Long? = null
    private var lastSuccess: Long? = null
    private var lastFailure: Throwable? = null

    init {
        require(failureThreshold > 0) { "failureThreshold must be positive" }
        require(probeIntervalMs >= 0) { "probeIntervalMs must not be negative" }
    }

    /** Returns null when OPEN and the probe interval has not elapsed. */
    suspend fun <T> execute(operation: suspend () -> T): T? {
        val permit = mutex.withLock {
            when (currentState) {
                State.CLOSED -> true
                State.HALF_OPEN -> false
                State.OPEN -> {
                    val opened = openedAt ?: return@withLock false
                    if (nowMs() - opened < probeIntervalMs) return@withLock false
                    currentState = State.HALF_OPEN
                    true
                }
            }
        }
        if (!permit) return null
        return try {
            operation().also { recordSuccess() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            recordFailure(failure)
            throw failure
        }
    }

    suspend fun snapshot(): Snapshot = mutex.withLock {
        Snapshot(currentState, consecutiveFailures, openedAt, lastSuccess, lastFailure)
    }

    private suspend fun recordSuccess() = mutex.withLock {
        currentState = State.CLOSED
        consecutiveFailures = 0
        openedAt = null
        lastSuccess = nowMs()
        lastFailure = null
    }

    private suspend fun recordFailure(failure: Throwable) = mutex.withLock {
        lastFailure = failure
        if (currentState == State.HALF_OPEN || ++consecutiveFailures >= failureThreshold) {
            currentState = State.OPEN
            openedAt = nowMs()
        }
    }
}
