package io.openeden.runtime

interface DiaryTaskStore {
    suspend fun enqueue(task: DiaryTask): Set<String>

    /** Enqueues by deterministic task ID; existing IDs must be treated as a no-op. */
    suspend fun enqueueIfAbsent(task: DiaryTask): Set<String>

    suspend fun leaseNext(sessionId: String, nowMs: Long, leaseMs: Long): DiaryTask?
    suspend fun complete(taskId: String)
    suspend fun complete(taskId: String, leaseToken: String) = complete(taskId)

    /** Atomically completes the task with its durable coverage marker when supported. */
    suspend fun completeWithCheckpoint(taskId: String, checkpoint: DiaryCheckpoint) {
        complete(taskId)
    }
    suspend fun completeWithCheckpoint(taskId: String, leaseToken: String, checkpoint: DiaryCheckpoint) {
        complete(taskId, leaseToken)
    }
    suspend fun fail(taskId: String, nowMs: Long, error: String, maxAttempts: Int = 5)
    suspend fun recoverExpired(nowMs: Long)
}
