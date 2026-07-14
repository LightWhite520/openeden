package io.openeden.runtime.diary

import io.openeden.bio.BioVector
import io.openeden.memory.MemoryEntry
import io.openeden.memory.MemoryStore
import io.openeden.memory.RetrievalMode
import io.openeden.memory.RetrievalRequest
import io.openeden.memory.RetrievalResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DiaryWorkerSchedulerTest {
    @Test
    fun `run once recovers leases and processes sessions in deterministic order`() = runTest {
        val events = mutableListOf<String>()
        val store = object : DiaryTaskStore {
            override suspend fun enqueue(task: DiaryTask) = emptySet<String>()
            override suspend fun enqueueIfAbsent(task: DiaryTask) = emptySet<String>()
            override suspend fun leaseNext(sessionId: String, nowMs: Long, leaseMs: Long): DiaryTask? = null
            override suspend fun complete(taskId: String) = Unit
            override suspend fun fail(taskId: String, nowMs: Long, error: String, maxAttempts: Int) = Unit
            override suspend fun recoverExpired(nowMs: Long) { events += "recover:$nowMs" }
        }
        val worker = DurableDiaryWorker(
            taskStore = store,
            memoryStore = EmptyMemoryStore,
            generator = DiaryNarrativeGenerator { error("not called") },
        )
        val scheduler = DiaryWorkerScheduler(store, worker, { setOf("S2", "S1") }, { 42L })

        scheduler.runOnce()

        assertEquals(listOf("recover:42"), events)
    }
}

private object EmptyMemoryStore : MemoryStore {
    override suspend fun write(entry: MemoryEntry): Set<String> = emptySet()
    override suspend fun stableVectors(sessionId: String, limit: Int): List<BioVector> = emptyList()
    override suspend fun retrieve(request: RetrievalRequest): RetrievalResult = RetrievalResult(
        mode = request.mode,
        injectionLabel = "",
        memories = emptyList(),
    )
}
