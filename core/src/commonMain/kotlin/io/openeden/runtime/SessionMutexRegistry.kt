package io.openeden.runtime

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SessionMutexRegistry {
    private val registryMutex = Mutex()
    private val mutexes = mutableMapOf<String, Mutex>()

    suspend fun forSession(sessionId: String): Mutex = registryMutex.withLock {
        mutexes.getOrPut(sessionId) { Mutex() }
    }
}
