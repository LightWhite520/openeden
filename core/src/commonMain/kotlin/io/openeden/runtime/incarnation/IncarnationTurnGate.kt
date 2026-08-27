package io.openeden.runtime.incarnation

import kotlinx.coroutines.sync.withLock

class IncarnationTurnGate(
    private val registry: IncarnationMutexRegistry,
) {
    suspend fun <T> withIncarnation(incarnationId: String, block: suspend () -> T): T =
        registry.forIncarnation(incarnationId).withLock { block() }
}
