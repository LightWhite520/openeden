package io.openeden.runtime

import io.openeden.memory.MemoryEntry
import io.openeden.memory.MemoryStore
import kotlinx.coroutines.CancellationException

data class DiaryNarrativeResult(val entry: MemoryEntry, val coveredRawMemoryId: String)

fun interface DiaryNarrativeGenerator {
    suspend fun generate(task: DiaryTask): DiaryNarrativeResult
}

class DurableDiaryWorker(
    private val taskStore: DiaryTaskStore,
    private val memoryStore: MemoryStore,
    private val generator: DiaryNarrativeGenerator,
    private val gate: SessionTurnGate = SessionTurnGate(SessionMutexRegistry()),
    private val leaseMs: Long = 60_000L,
) {
    suspend fun processNext(sessionId: String, nowMs: Long): Boolean = gate.withSession(sessionId) {
        val task = taskStore.leaseNext(sessionId, nowMs, leaseMs) ?: return@withSession false
        try {
            val result = generator.generate(task)
            val narrative = result.entry
            require(result.coveredRawMemoryId.isNotBlank()) { "Diary result coverage bound is required" }
            require(narrative.kind == io.openeden.memory.MemoryKind.NARRATIVE) {
                "Diary generator must produce NARRATIVE memory"
            }
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
