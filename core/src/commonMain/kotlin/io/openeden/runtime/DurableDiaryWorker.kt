package io.openeden.runtime

import io.openeden.memory.MemoryEntry
import io.openeden.memory.MemoryStore
import kotlinx.coroutines.CancellationException

fun interface DiaryNarrativeGenerator {
    suspend fun generate(task: DiaryTask): MemoryEntry
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
            val narrative = generator.generate(task)
            require(narrative.kind == io.openeden.memory.MemoryKind.NARRATIVE) {
                "Diary generator must produce NARRATIVE memory"
            }
            memoryStore.write(narrative)
            taskStore.completeWithCheckpoint(
                task.id,
                DiaryCheckpoint(
                    lastCoveredRawMemoryId = task.sourceMemoryId,
                    lastSuccessfulDiaryAtMs = nowMs,
                    lastNarrativeMemoryId = narrative.id,
                ),
            )
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            taskStore.fail(task.id, nowMs, error.message ?: error::class.simpleName.orEmpty())
            false
        }
    }
}
