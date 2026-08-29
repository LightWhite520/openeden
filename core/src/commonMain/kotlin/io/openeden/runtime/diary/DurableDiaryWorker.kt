package io.openeden.runtime.diary

import io.openeden.runtime.session.SessionMutexRegistry
import io.openeden.runtime.session.SessionTurnGate
import io.openeden.runtime.incarnation.IncarnationTurnGate

import io.openeden.memory.MemoryEntry
import io.openeden.memory.MemoryStore
import kotlinx.coroutines.CancellationException

data class DiaryNarrativeResult(val entry: MemoryEntry, val coveredRawMemoryId: String)

fun interface DiaryNarrativeGenerator {
    suspend fun generate(task: DiaryTask): DiaryNarrativeResult
}

class DurableDiaryWorker private constructor(
    private val taskStore: DiaryTaskStore,
    private val memoryStore: MemoryStore,
    private val generator: DiaryNarrativeGenerator,
    private val incarnationGate: IncarnationTurnGate?,
    private val activeIncarnationId: (suspend () -> String)?,
    private val leaseMs: Long = 60_000L,
    @Suppress("UNUSED_PARAMETER") constructorMarker: Unit,
) {
    @Deprecated(
        message = "Diary execution requires the live runtime incarnation mutation gate",
        level = DeprecationLevel.WARNING,
    )
    constructor(
        taskStore: DiaryTaskStore,
        memoryStore: MemoryStore,
        generator: DiaryNarrativeGenerator,
        @Suppress("UNUSED_PARAMETER") gate: SessionTurnGate = SessionTurnGate(SessionMutexRegistry()),
        leaseMs: Long = 60_000L,
    ) : this(taskStore, memoryStore, generator, null, null, leaseMs, Unit)

    constructor(
        taskStore: DiaryTaskStore,
        memoryStore: MemoryStore,
        generator: DiaryNarrativeGenerator,
        incarnationGate: IncarnationTurnGate,
        activeIncarnationId: suspend () -> String,
        leaseMs: Long = 60_000L,
    ) : this(taskStore, memoryStore, generator, incarnationGate, activeIncarnationId, leaseMs, Unit)

    suspend fun processNext(sessionId: String, nowMs: Long): Boolean {
        val authoritativeGate = checkNotNull(incarnationGate) {
            "Diary worker is disabled without the live runtime incarnation mutation gate"
        }
        val activeId = checkNotNull(activeIncarnationId) {
            "Diary worker is disabled without an authoritative active-incarnation resolver"
        }
        val expectedIncarnationId = activeId()
        return authoritativeGate.withIncarnation(expectedIncarnationId) {
            if (activeId() != expectedIncarnationId) return@withIncarnation false
            val task = taskStore.leaseNext(sessionId, nowMs, leaseMs) ?: return@withIncarnation false
        try {
            if (task.incarnationId != expectedIncarnationId) {
                taskStore.fail(
                    task.id,
                    task.leaseToken ?: "",
                    nowMs,
                    "StaleIncarnation",
                )
                return@withIncarnation false
            }
            val result = generator.generate(task)
            val narrative = result.entry
            require(result.coveredRawMemoryId.isNotBlank()) { "Diary result coverage bound is required" }
            require(narrative.kind == io.openeden.memory.MemoryKind.NARRATIVE) {
                "Diary generator must produce NARRATIVE memory"
            }
            require(narrative.metadata.incarnationId == task.incarnationId) {
                "Diary generator returned memory for a different incarnation"
            }
            if (activeId() != expectedIncarnationId) return@withIncarnation false
            memoryStore.write(narrative)
            val completed = taskStore.completeWithCheckpointIfOwned(
                task.id, task.leaseToken ?: "",
                DiaryCheckpoint(
                    lastCoveredRawMemoryId = result.coveredRawMemoryId,
                    lastSuccessfulDiaryAtMs = nowMs,
                    lastNarrativeMemoryId = narrative.id,
                ),
            )
            completed
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            taskStore.fail(task.id, task.leaseToken ?: "", nowMs, error.message ?: error::class.simpleName.orEmpty())
            false
        }
        }
    }
}
