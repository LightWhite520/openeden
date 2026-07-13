package io.openeden.runtime

interface DiaryTaskStore {
    suspend fun enqueue(task: DiaryTask): Set<String>

    /** Enqueues by deterministic task ID; existing IDs must be treated as a no-op. */
    suspend fun enqueueIfAbsent(task: DiaryTask): Set<String> = enqueue(task)

    suspend fun leaseNext(sessionId: String, nowMs: Long, leaseMs: Long): DiaryTask?
    suspend fun complete(taskId: String)
    suspend fun fail(taskId: String, nowMs: Long, error: String, maxAttempts: Int = 5)
    suspend fun recoverExpired(nowMs: Long)
}
