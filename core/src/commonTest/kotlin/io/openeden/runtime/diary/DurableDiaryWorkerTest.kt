package io.openeden.runtime.diary

import io.openeden.runtime.inference.DirectInferenceExecutor
import io.openeden.runtime.incarnation.IncarnationMutexRegistry
import io.openeden.runtime.incarnation.IncarnationTurnGate


import io.openeden.bio.BioVector
import io.openeden.bio.VectorDelta
import io.openeden.memory.InMemoryMemoryPalace
import io.openeden.memory.MemoryEntry
import io.openeden.memory.MemoryKind
import io.openeden.memory.MemoryMetadata
import io.openeden.memory.MemoryRoom
import io.openeden.memory.MemoryVisibility
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DurableDiaryWorkerTest {
    @Test
    fun `reset winning the shared incarnation gate prevents a stale diary lease and write`() = runTest {
        val taskStore = TestDiaryTaskStore(
            DiaryTask("task:stale", "S", "raw:1", "vector_delta", incarnationId = "inc-1"),
        )
        val memory = InMemoryMemoryPalace(DirectInferenceExecutor)
        val gate = IncarnationTurnGate(IncarnationMutexRegistry())
        var activeIncarnationId = "inc-1"
        val resetEntered = CompletableDeferred<Unit>()
        val releaseReset = CompletableDeferred<Unit>()
        val reset = launch {
            gate.withIncarnation("inc-1") {
                resetEntered.complete(Unit)
                releaseReset.await()
            }
        }
        resetEntered.await()
        val worker = DurableDiaryWorker(
            taskStore = taskStore,
            memoryStore = memory,
            generator = DiaryNarrativeGenerator { error("stale diary must not run inference") },
            incarnationGate = gate,
            activeIncarnationId = { activeIncarnationId },
        )
        val processing = async { worker.processNext("S", 100) }
        testScheduler.runCurrent()
        assertEquals(0, taskStore.leaseCount)

        activeIncarnationId = "inc-2"
        releaseReset.complete(Unit)
        reset.join()

        assertEquals(false, processing.await())
        assertEquals(0, taskStore.leaseCount)
        assertEquals(DiaryTaskStatus.PENDING, taskStore.task.status)
    }

    @Test
    fun `shared incarnation gate covers diary lease through checkpoint completion`() = runTest {
        val taskStore = TestDiaryTaskStore(
            DiaryTask("task:1", "S", "raw:1", "vector_delta", incarnationId = "inc-1"),
        )
        val memory = InMemoryMemoryPalace(DirectInferenceExecutor)
        val gate = IncarnationTurnGate(IncarnationMutexRegistry())
        val inferenceEntered = CompletableDeferred<Unit>()
        val finishInference = CompletableDeferred<Unit>()
        val resetEntered = CompletableDeferred<Unit>()
        val worker = DurableDiaryWorker(
            taskStore = taskStore,
            memoryStore = memory,
            generator = DiaryNarrativeGenerator { task ->
                inferenceEntered.complete(Unit)
                finishInference.await()
                DiaryNarrativeResult(narrative(task), "raw:1")
            },
            incarnationGate = gate,
            activeIncarnationId = { "inc-1" },
        )

        val processing = async { worker.processNext("S", 100) }
        inferenceEntered.await()
        val reset = launch {
            gate.withIncarnation("inc-1") { resetEntered.complete(Unit) }
        }
        testScheduler.runCurrent()
        assertEquals(false, resetEntered.isCompleted)

        finishInference.complete(Unit)
        assertTrue(processing.await())
        reset.join()
        assertTrue(resetEntered.isCompleted)
        assertEquals(DiaryTaskStatus.DONE, taskStore.task.status)
    }

    @Test
    fun `worker writes narrative memory and completes task without state mutation`() = runTest {
        val taskStore = TestDiaryTaskStore(
            DiaryTask("task:1", "S", "raw:1", "vector_delta", incarnationId = "inc-1"),
        )
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
                        incarnationId = "inc-1",
                        sourceSessionId = "S",
                        visibility = MemoryVisibility.ScopeShared("S"),
                    ),
                ), "raw:actual")
            },
            incarnationGate = IncarnationTurnGate(IncarnationMutexRegistry()),
            activeIncarnationId = { "inc-1" },
        )

        assertTrue(worker.processNext("S", 100))
        assertEquals(DiaryTaskStatus.DONE, taskStore.task.status)
        val result = memory.retrieve(
            io.openeden.memory.RetrievalRequest(
                "S",
                "distilled",
                BioVector.Neutral,
                BioVector.Neutral,
                io.openeden.memory.RetrievalMode.CONGRUENT,
                incarnationId = "inc-1",
            ),
        )
        assertEquals("distilled narrative", result.memories.single().content)
    }

    private fun narrative(task: DiaryTask) = MemoryEntry(
        id = "narrative:${task.id}",
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
            incarnationId = task.incarnationId,
            sourceSessionId = task.sessionId,
            visibility = MemoryVisibility.ScopeShared(task.sessionId),
        ),
    )
}

private class TestDiaryTaskStore(
    var task: DiaryTask,
) : DiaryTaskStore {
    var leaseCount = 0
    override suspend fun enqueue(task: DiaryTask): Set<String> = emptySet()
    override suspend fun enqueueIfAbsent(task: DiaryTask): Set<String> = emptySet()
    override suspend fun leaseNext(sessionId: String, nowMs: Long, leaseMs: Long): DiaryTask? =
        task.takeIf { it.sessionId == sessionId && it.status == DiaryTaskStatus.PENDING }
            ?.also {
                leaseCount += 1
                task = it.copy(status = DiaryTaskStatus.RUNNING, leaseExpiresAtMs = nowMs + leaseMs)
            }
    override suspend fun complete(taskId: String) { task = task.copy(status = DiaryTaskStatus.DONE) }
    override suspend fun fail(taskId: String, nowMs: Long, error: String, maxAttempts: Int) {
        task = task.copy(status = DiaryTaskStatus.DEAD, lastError = error)
    }
    override suspend fun recoverExpired(nowMs: Long) = Unit
}
