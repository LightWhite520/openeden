package io.openeden.runtime.diary

import io.openeden.bio.BioVector
import io.openeden.memory.MemoryEntry
import io.openeden.memory.MemoryStore
import io.openeden.memory.RetrievalMode
import io.openeden.memory.RetrievalRequest
import io.openeden.memory.RetrievalResult
import io.openeden.runtime.incarnation.IncarnationMutexRegistry
import io.openeden.runtime.incarnation.IncarnationTurnGate
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DiaryWorkerSchedulerTest {
    @Test
    fun `polling survives recovery and lease failures but propagates cancellation`() = runTest {
        var recoveries = 0
        var leases = 0
        var cancelNext = false
        val failures = mutableListOf<Throwable>()
        val store = object : DiaryTaskStore {
            override suspend fun enqueue(task: DiaryTask) = emptySet<String>()
            override suspend fun enqueueIfAbsent(task: DiaryTask) = emptySet<String>()
            override suspend fun leaseNext(sessionId: String, nowMs: Long, leaseMs: Long): DiaryTask? {
                if (++leases == 1) error("lease contention")
                return null
            }
            override suspend fun complete(taskId: String) = Unit
            override suspend fun fail(taskId: String, nowMs: Long, error: String, maxAttempts: Int) = Unit
            override suspend fun recoverExpired(nowMs: Long) {
                if (cancelNext) throw CancellationException("shutdown")
                if (++recoveries == 1) error("recovery contention")
            }
        }
        val worker = DurableDiaryWorker(store, EmptyMemoryStore,
            DiaryNarrativeGenerator { error("not called") },
            IncarnationTurnGate(IncarnationMutexRegistry()), { "inc-1" })
        val scheduler = DiaryWorkerScheduler(store, worker, { setOf("S") }, { 42L }, { failures += it })
        val job = scheduler.start(backgroundScope, pollIntervalMs = 100)
        runCurrent()
        repeat(2) { advanceTimeBy(100); runCurrent() }
        assertEquals(3, recoveries)
        assertEquals(2, leases)
        assertEquals(2, failures.size)
        assertTrue(job.isActive)
        cancelNext = true
        advanceTimeBy(100)
        runCurrent()
        assertTrue(job.isCancelled)
        assertEquals(2, failures.size)
    }

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
            incarnationGate = IncarnationTurnGate(IncarnationMutexRegistry()),
            activeIncarnationId = { "inc-1" },
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
