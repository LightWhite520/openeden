package io.openeden.runtime

import io.openeden.trace.TraceTag
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

data class DiaryEvent(
    val sessionId: String,
    val traceId: String,
    val reason: String,
)

enum class DiaryTaskStatus { PENDING, RUNNING, DONE, DEAD }

data class DiaryTask(
    val id: String,
    val sessionId: String,
    val sourceMemoryId: String?,
    val reason: String,
    val status: DiaryTaskStatus = DiaryTaskStatus.PENDING,
    val attempts: Int = 0,
    val availableAtMs: Long = 0L,
    val leaseExpiresAtMs: Long? = null,
    val lastError: String? = null,
)

interface DiaryTaskStore {
    suspend fun enqueue(task: DiaryTask): Set<String>
    suspend fun leaseNext(sessionId: String, nowMs: Long, leaseMs: Long): DiaryTask?
    suspend fun complete(taskId: String)
    suspend fun fail(taskId: String, nowMs: Long, error: String, maxAttempts: Int = 5)
    suspend fun recoverExpired(nowMs: Long)
}

class SessionDiaryQueue(
    capacity: Int = 8,
) {
    private val channel = Channel<DiaryEvent>(
        capacity = capacity,
        onBufferOverflow = BufferOverflow.DROP_LATEST,
    )

    fun events(): Flow<DiaryEvent> = channel.receiveAsFlow()

    fun tryEnqueue(event: DiaryEvent): Set<String> =
        if (channel.trySend(event).isSuccess) {
            emptySet()
        } else {
            setOf(TraceTag.DiaryQueueOverflow)
        }
}
