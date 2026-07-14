package io.openeden.runtime.diary


import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DurableDiaryContractTest {
    @Test
    fun `diary store enforces bounded session queue and lease recovery`() = runTest {
        val store = FakeDiaryTaskStore(maxPendingPerSession = 8)
        repeat(8) { index ->
            assertTrue(store.enqueue(DiaryTask("t$index", "S", null, "vector_delta")).isEmpty())
        }
        assertTrue(store.enqueue(DiaryTask("overflow", "S", null, "vector_delta")).isNotEmpty())
        val leased = store.leaseNext("S", nowMs = 100, leaseMs = 10)
        assertEquals("t0", leased?.id)
        store.recoverExpired(111)
        assertEquals(DiaryTaskStatus.PENDING, store.tasks.single { it.id == "t0" }.status)
    }
}

private class FakeDiaryTaskStore(
    private val maxPendingPerSession: Int,
) : DiaryTaskStore {
    val tasks = mutableListOf<DiaryTask>()

    override suspend fun enqueue(task: DiaryTask): Set<String> {
        if (tasks.count { it.sessionId == task.sessionId && it.status in setOf(DiaryTaskStatus.PENDING, DiaryTaskStatus.RUNNING) } >= maxPendingPerSession) {
            return setOf(io.openeden.trace.TraceTag.DiaryQueueOverflow)
        }
        tasks += task
        return emptySet()
    }

    override suspend fun enqueueIfAbsent(task: DiaryTask): Set<String> {
        if (tasks.any { it.id == task.id }) return emptySet()
        return enqueue(task)
    }

    override suspend fun leaseNext(sessionId: String, nowMs: Long, leaseMs: Long): DiaryTask? {
        val index = tasks.indexOfFirst { it.sessionId == sessionId && it.status == DiaryTaskStatus.PENDING && it.availableAtMs <= nowMs }
        if (index < 0) return null
        tasks[index] = tasks[index].copy(status = DiaryTaskStatus.RUNNING, leaseExpiresAtMs = nowMs + leaseMs)
        return tasks[index]
    }

    override suspend fun complete(taskId: String) { update(taskId) { it.copy(status = DiaryTaskStatus.DONE) } }

    override suspend fun fail(taskId: String, nowMs: Long, error: String, maxAttempts: Int) {
        update(taskId) {
            val attempts = it.attempts + 1
            it.copy(
                status = if (attempts >= maxAttempts) DiaryTaskStatus.DEAD else DiaryTaskStatus.PENDING,
                attempts = attempts,
                availableAtMs = nowMs + (1L shl attempts.coerceAtMost(10)) * 1000L,
                lastError = error,
            )
        }
    }

    override suspend fun recoverExpired(nowMs: Long) {
        tasks.replaceAll { task ->
            if (task.status == DiaryTaskStatus.RUNNING && (task.leaseExpiresAtMs ?: Long.MAX_VALUE) <= nowMs) {
                task.copy(status = DiaryTaskStatus.PENDING, leaseExpiresAtMs = null)
            } else task
        }
    }

    private fun update(id: String, transform: (DiaryTask) -> DiaryTask) {
        val index = tasks.indexOfFirst { it.id == id }
        if (index >= 0) tasks[index] = transform(tasks[index])
    }
}
