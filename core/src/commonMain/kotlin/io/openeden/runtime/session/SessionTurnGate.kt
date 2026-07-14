package io.openeden.runtime.session

import kotlinx.coroutines.sync.withLock

class SessionTurnGate(
    private val registry: SessionMutexRegistry,
) {
    suspend fun <T> withSession(sessionId: String, block: suspend () -> T): T =
        registry.forSession(sessionId).withLock { block() }
}
