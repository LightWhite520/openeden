package io.openeden.runtime

import io.openeden.bio.BioVector
import io.openeden.bio.VectorDelta
import io.openeden.memory.InMemoryMemoryPalace
import io.openeden.memory.MemoryEntry
import io.openeden.memory.MemoryKind
import io.openeden.memory.MemoryMetadata
import io.openeden.memory.MemoryRoom
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DurableDiaryWorkerTest {
    @Test
    fun `worker writes narrative memory and completes task without state mutation`() = runTest {
        val taskStore = TestDiaryTaskStore(DiaryTask("task:1", "S", "raw:1", "vector_delta"))
        val memory = InMemoryMemoryPalace(DirectInferenceExecutor)
        val worker = DurableDiaryWorker(
            taskStore = taskStore,
            memoryStore = memory,
            generator = DiaryNarrativeGenerator { task ->
                DiaryNarrativeResult(MemoryEntry(
                    id = "narrative:1",
                    sessionId = task.sessionId,
                    content = "distilled narrative",
                    room = MemoryRoom.EVENT_ROOM,
                    kind = MemoryKind.NARRATIVE,
                    semanticEmbedding = InMemoryMemoryPalace.embedText("distilled narrative"),
                    emotionalEmbedding = BioVector.Neutral.toList(),
                    metadata = MemoryMetadata(
                        BioVector.Neutral,
                        0.4f,
                        VectorDelta.Zero,
                        BioVector.Neutral,
                        "diary",
                    ),
                ), "raw:actual")
            },
        )

        assertTrue(worker.processNext("S", 100))
        assertEquals(DiaryTaskStatus.DONE, taskStore.task.status)
        val result = memory.retrieve(
            io.openeden.memory.RetrievalRequest(
                "S", "distilled", BioVector.Neutral, BioVector.Neutral, io.openeden.memory.RetrievalMode.CONGRUENT,
            ),
        )
        assertEquals("distilled narrative", result.memories.single().content)
    }
}

private class TestDiaryTaskStore(
    var task: DiaryTask,
) : DiaryTaskStore {
    override suspend fun enqueue(task: DiaryTask): Set<String> = emptySet()
    override suspend fun enqueueIfAbsent(task: DiaryTask): Set<String> = emptySet()
    override suspend fun leaseNext(sessionId: String, nowMs: Long, leaseMs: Long): DiaryTask? =
        task.takeIf { it.sessionId == sessionId && it.status == DiaryTaskStatus.PENDING }
            ?.also { task = it.copy(status = DiaryTaskStatus.RUNNING, leaseExpiresAtMs = nowMs + leaseMs) }
    override suspend fun complete(taskId: String) { task = task.copy(status = DiaryTaskStatus.DONE) }
    override suspend fun fail(taskId: String, nowMs: Long, error: String, maxAttempts: Int) {
        task = task.copy(status = DiaryTaskStatus.DEAD, lastError = error)
    }
    override suspend fun recoverExpired(nowMs: Long) = Unit
}
