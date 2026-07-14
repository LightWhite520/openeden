package io.openeden.runtime.diary

import io.openeden.trace.TraceTag
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

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
