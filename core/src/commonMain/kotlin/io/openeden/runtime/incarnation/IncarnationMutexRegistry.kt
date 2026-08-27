package io.openeden.runtime.incarnation

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class IncarnationMutexRegistry {
    private val mutexes = mutableMapOf<String, Mutex>()
    private val registryMutex = Mutex()

    suspend fun forIncarnation(incarnationId: String): Mutex = registryMutex.withLock {
        mutexes.getOrPut(incarnationId, ::Mutex)
    }
}
