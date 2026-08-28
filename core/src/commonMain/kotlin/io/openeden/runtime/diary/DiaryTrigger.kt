package io.openeden.runtime.diary

import io.openeden.bio.VectorDelta
import io.openeden.memory.MemoryMetadata
import kotlin.math.abs

data class DiaryTriggerConfig(
    val deltaThreshold: Float = 0.25f,
    val elapsedIntervalMs: Long = 5L * 60L * 60L * 1000L,
) {
    init {
        require(deltaThreshold in 0.0f..1.0f) { "deltaThreshold must be in [0, 1]" }
        require(elapsedIntervalMs > 0L) { "elapsedIntervalMs must be positive" }
    }
}

/** Coordinates durable Diary task creation; it performs no inference or narrative generation. */
class DiaryTriggerCoordinator(
    private val taskStore: DiaryTaskStore,
    private val checkpointStore: DiaryCheckpointStore,
    private val rawMemorySource: DiaryRawMemorySource,
    private val config: DiaryTriggerConfig = DiaryTriggerConfig(),
) {
    suspend fun onVectorDelta(
        sessionId: String,
        rawMemoryId: String,
        delta: VectorDelta,
        nowMs: Long,
    ): Set<String> = onVectorDelta(sessionId, rawMemoryId, delta, metadata = null, nowMs)

    suspend fun onVectorDelta(
        sessionId: String,
        rawMemoryId: String,
        delta: VectorDelta,
        metadata: MemoryMetadata?,
        nowMs: Long,
    ): Set<String> {
        if (delta.toList().maxOf(::abs) < config.deltaThreshold) return emptySet()
        return enqueue(sessionId, REASON_VECTOR_DELTA, rawMemoryId, metadata, nowMs)
    }

    suspend fun onContextCompacted(
        sessionId: String,
        lastCoveredRawMemoryId: String,
        nowMs: Long,
    ): Set<String> = onContextCompacted(sessionId, lastCoveredRawMemoryId, nowMs, metadata = null)

    suspend fun onContextCompacted(
        sessionId: String,
        lastCoveredRawMemoryId: String,
        nowMs: Long,
        metadata: MemoryMetadata,
    ): Set<String> = enqueue(sessionId, REASON_CONTEXT_COMPACTED, lastCoveredRawMemoryId, metadata, nowMs)

    suspend fun flushElapsedSessions(nowMs: Long): Map<String, Set<String>> {
        val sessions = rawMemorySource.sessionsWithRawMemories()
        val result = linkedMapOf<String, Set<String>>()
        for (sessionId in sessions.sorted()) {
            val latest = rawMemorySource.latestRawMemory(sessionId) ?: continue
            val checkpoint = checkpointStore.read(sessionId)
            if (checkpoint?.lastCoveredRawMemoryId == latest.id) continue
            val firstUncovered = rawMemorySource.firstRawMemoryAfter(sessionId, checkpoint?.lastCoveredRawMemoryId) ?: continue
            val baseline = checkpoint?.lastSuccessfulDiaryAtMs ?: firstUncovered.createdAtMs
            if (nowMs - baseline < config.elapsedIntervalMs) continue
            result[sessionId] = enqueue(sessionId, REASON_ELAPSED, latest.id, latest.metadata, nowMs)
        }
        return result
    }

    private suspend fun enqueue(
        sessionId: String,
        reason: String,
        upperBoundRawMemoryId: String,
        metadata: MemoryMetadata?,
        nowMs: Long,
    ): Set<String> {
        val legacy = metadata == null
        val task = DiaryTask(
            id = taskId(sessionId, reason, upperBoundRawMemoryId),
            sessionId = sessionId,
            sourceMemoryId = upperBoundRawMemoryId,
            reason = reason,
            availableAtMs = nowMs,
            incarnationId = metadata?.incarnationId.orEmpty(),
            sourceSessionId = metadata?.sourceSessionId.orEmpty().ifBlank { sessionId },
            platform = sessionId.substringBefore(':', sessionId),
            userId = metadata?.userId.orEmpty(),
            canonicalSubjectId = metadata?.canonicalSubjectId.orEmpty(),
            visibility = metadata?.visibility ?: io.openeden.memory.MemoryVisibility.OperatorOnly,
        )
        return taskStore.enqueueIfAbsent(task)
    }

    companion object {
        const val REASON_VECTOR_DELTA = "vector_delta"
        const val REASON_CONTEXT_COMPACTED = "context_compacted"
        const val REASON_ELAPSED = "elapsed"

        fun taskId(sessionId: String, reason: String, upperBoundRawMemoryId: String): String =
            "$sessionId|$reason|$upperBoundRawMemoryId"
    }
}
