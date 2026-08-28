package io.openeden.runtime.diary

import io.openeden.bio.VectorDelta
import io.openeden.memory.MemoryMetadata
import io.openeden.trace.TraceTag
import kotlin.math.abs

enum class DiaryTriggerOutcome(
    val traceTags: Set<String>,
) {
    SKIPPED_BELOW_THRESHOLD(emptySet()),
    ENQUEUED(emptySet()),
    OVERFLOW(setOf(TraceTag.DiaryQueueOverflow)),
    ;

    companion object {
        fun fromTraceTags(traceTags: Set<String>): DiaryTriggerOutcome =
            if (TraceTag.DiaryQueueOverflow in traceTags) OVERFLOW else ENQUEUED
    }
}

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
    ): DiaryTriggerOutcome = onVectorDelta(sessionId, rawMemoryId, delta, metadata = null, nowMs)

    suspend fun onVectorDelta(
        sessionId: String,
        rawMemoryId: String,
        delta: VectorDelta,
        metadata: MemoryMetadata?,
        nowMs: Long,
    ): DiaryTriggerOutcome {
        if (delta.toList().maxOf(::abs) < config.deltaThreshold) {
            return DiaryTriggerOutcome.SKIPPED_BELOW_THRESHOLD
        }
        return enqueue(sessionId, REASON_VECTOR_DELTA, rawMemoryId, metadata, nowMs)
    }

    suspend fun onContextCompacted(
        sessionId: String,
        lastCoveredRawMemoryId: String,
        nowMs: Long,
    ): DiaryTriggerOutcome = onContextCompacted(sessionId, lastCoveredRawMemoryId, nowMs, metadata = null)

    suspend fun onContextCompacted(
        sessionId: String,
        lastCoveredRawMemoryId: String,
        nowMs: Long,
        metadata: MemoryMetadata?,
    ): DiaryTriggerOutcome = enqueue(sessionId, REASON_CONTEXT_COMPACTED, lastCoveredRawMemoryId, metadata, nowMs)

    suspend fun flushElapsedSessions(nowMs: Long): Map<String, DiaryTriggerOutcome> {
        val sessions = rawMemorySource.sessionsWithRawMemories()
        val result = linkedMapOf<String, DiaryTriggerOutcome>()
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
    ): DiaryTriggerOutcome {
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
        return DiaryTriggerOutcome.fromTraceTags(taskStore.enqueueIfAbsent(task))
    }

    companion object {
        const val REASON_VECTOR_DELTA = "vector_delta"
        const val REASON_CONTEXT_COMPACTED = "context_compacted"
        const val REASON_ELAPSED = "elapsed"

        fun taskId(sessionId: String, reason: String, upperBoundRawMemoryId: String): String =
            "$sessionId|$reason|$upperBoundRawMemoryId"
    }
}
