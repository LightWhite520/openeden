package io.openeden.runtime.lifecycle

interface IncarnationLifecycleStore {
    suspend fun read(): IncarnationLifecycle

    suspend fun markCritical(): IncarnationLifecycle

    suspend fun beginTermination(): IncarnationLifecycle

    suspend fun markTerminated(): IncarnationLifecycle

    suspend fun createFresh(requestId: String, nowMs: Long): String
}
