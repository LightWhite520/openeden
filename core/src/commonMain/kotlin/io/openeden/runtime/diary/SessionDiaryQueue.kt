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

    fun tryEnqueue(event: DiaryEvent): Set<String> {
        val sourceSessionId = event.visibility.let { visibility ->
            (visibility as? io.openeden.memory.MemoryVisibility.ScopeShared)?.sessionId
                ?.takeIf { it.isNotBlank() }
                ?: event.sessionId
        }
        val normalized = event.copy(
            visibility = when (val visibility = event.visibility) {
                is io.openeden.memory.MemoryVisibility.ScopeShared -> visibility.copy(sessionId = sourceSessionId)
                else -> visibility
            },
        )
        return if (channel.trySend(normalized).isSuccess) {
            emptySet()
        } else {
            setOf(TraceTag.DiaryQueueOverflow)
        }
    }
}
