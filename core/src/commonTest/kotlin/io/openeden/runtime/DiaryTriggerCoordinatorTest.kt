package io.openeden.runtime

import io.openeden.bio.VectorDelta
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiaryTriggerCoordinatorTest {
    @Test
    fun `vector delta triggers at max absolute threshold and is idempotent`() = runTest {
        val store = RecordingDiaryTaskStore()
        val checkpoints = InMemoryDiaryCheckpointStore()
        val coordinator = DiaryTriggerCoordinator(store, checkpoints, InMemoryDiaryRawMemorySource())

        assertTrue(coordinator.onVectorDelta("S", "raw-1", VectorDelta(p = 0.249f), 100L).isEmpty())
        assertTrue(coordinator.onVectorDelta("S", "raw-1", VectorDelta(p = 0.25f), 101L).isEmpty())
        assertTrue(coordinator.onVectorDelta("S", "raw-1", VectorDelta(p = 0.25f), 102L).isEmpty())
        assertEquals(1, store.tasks.size)
        assertEquals("vector_delta", store.tasks.single().reason)
    }

    @Test
    fun `elapsed trigger requires five hours and newer raw memory`() = runTest {
        val store = RecordingDiaryTaskStore()
        val checkpoints = InMemoryDiaryCheckpointStore()
        val raw = InMemoryDiaryRawMemorySource()
        raw.put("S", "raw-2", 1L)
        checkpoints.put("S", DiaryCheckpoint(lastCoveredRawMemoryId = "raw-1", lastSuccessfulDiaryAtMs = 0L))
        val coordinator = DiaryTriggerCoordinator(store, checkpoints, raw)

        assertTrue(coordinator.flushElapsedSessions(5L * 60 * 60 * 1000 - 1).isEmpty())
        val result = coordinator.flushElapsedSessions(5L * 60 * 60 * 1000)
        assertTrue(result.containsKey("S"))
        assertEquals("elapsed", store.tasks.single().reason)
    }

    @Test
    fun `context compaction uses covered memory as deterministic upper bound`() = runTest {
        val store = RecordingDiaryTaskStore()
        val coordinator = DiaryTriggerCoordinator(store, InMemoryDiaryCheckpointStore(), InMemoryDiaryRawMemorySource())

        coordinator.onContextCompacted("S", "raw-9", 42L)
        assertEquals("raw-9", store.tasks.single().sourceMemoryId)
        assertEquals("context_compacted", store.tasks.single().reason)
        assertEquals(store.tasks.single().id, DiaryTriggerCoordinator.taskId("S", "context_compacted", "raw-9"))
    }
}

private class RecordingDiaryTaskStore : DiaryTaskStore {
    val tasks = mutableListOf<DiaryTask>()
    override suspend fun enqueue(task: DiaryTask): Set<String> {
        if (tasks.any { it.id == task.id }) return emptySet()
        tasks += task
        return emptySet()
    }
    override suspend fun leaseNext(sessionId: String, nowMs: Long, leaseMs: Long): DiaryTask? = null
    override suspend fun complete(taskId: String) = Unit
    override suspend fun fail(taskId: String, nowMs: Long, error: String, maxAttempts: Int) = Unit
    override suspend fun recoverExpired(nowMs: Long) = Unit
}

private class InMemoryDiaryCheckpointStore : DiaryCheckpointStore {
    private val values = mutableMapOf<String, DiaryCheckpoint>()
    override suspend fun read(sessionId: String): DiaryCheckpoint? = values[sessionId]
    override suspend fun sessions(): Set<String> = values.keys
    fun put(sessionId: String, checkpoint: DiaryCheckpoint) { values[sessionId] = checkpoint }
}

private class InMemoryDiaryRawMemorySource : DiaryRawMemorySource {
    private val values = mutableMapOf<String, DiaryRawMemoryCursor>()
    override suspend fun sessionsWithRawMemories(): Set<String> = values.keys
    override suspend fun latestRawMemory(sessionId: String): DiaryRawMemoryCursor? = values[sessionId]
    fun put(sessionId: String, id: String, createdAtMs: Long) { values[sessionId] = DiaryRawMemoryCursor(id, createdAtMs) }
}
