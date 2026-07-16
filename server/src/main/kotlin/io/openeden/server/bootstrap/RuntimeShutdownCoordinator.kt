package io.openeden.server.bootstrap

import kotlinx.coroutines.Job

internal class RuntimeShutdownCoordinator(
    private val runtimeJob: Job,
    private val closers: List<() -> Unit>,
) {
    fun stopping() {
        runtimeJob.cancel()
    }

    fun stopped(): Throwable? = closeBestEffort(closers)
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
