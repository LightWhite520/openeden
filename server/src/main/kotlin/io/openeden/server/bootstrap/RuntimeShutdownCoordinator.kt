package io.openeden.server.bootstrap

import kotlinx.coroutines.Job

internal class RuntimeShutdownCoordinator(
    private val runtimeJob: Job,
    private val closers: List<suspend () -> Unit>,
) {
    fun stopping() {
        runtimeJob.cancel()
    }

    suspend fun stopped(): Throwable? = closeBestEffortSuspend(closers)
}

internal suspend fun closeBestEffortSuspend(closers: Iterable<suspend () -> Unit>): Throwable? {
    var firstFailure: Throwable? = null
    closers.forEach { close ->
        try {
            close()
        } catch (failure: Throwable) {
            if (firstFailure == null) {
                firstFailure = failure
            } else if (failure !== firstFailure) {
                runCatching { firstFailure.addSuppressed(failure) }
            }
        }
    }
    return firstFailure
}

internal fun closeBestEffort(closers: Iterable<() -> Unit>): Throwable? {
    var firstFailure: Throwable? = null
    closers.forEach { close ->
        try {
            close()
        } catch (failure: Throwable) {
            if (firstFailure == null) {
                firstFailure = failure
            } else if (failure !== firstFailure) {
                runCatching { firstFailure.addSuppressed(failure) }
            }
        }
    }
    return firstFailure
}
