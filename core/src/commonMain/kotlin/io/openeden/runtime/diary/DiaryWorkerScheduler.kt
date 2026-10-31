package io.openeden.runtime.diary

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class DiaryWorkerScheduler(
    private val taskStore: DiaryTaskStore,
    private val worker: DurableDiaryWorker,
    private val sessionIds: suspend () -> Set<String>,
    private val nowMs: () -> Long,
    private val onFailure: (Throwable) -> Unit = {},
) {
    suspend fun runOnce() {
        val now = nowMs()
        taskStore.recoverExpired(now)
        sessionIds().asSequence().sorted().forEach { sessionId ->
            worker.processNext(sessionId, now)
        }
    }

    fun start(scope: CoroutineScope, pollIntervalMs: Long = 5_000L): Job = scope.launch {
        while (isActive) {
            try {
                runOnce()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                onFailure(failure)
            }
            delay(pollIntervalMs.coerceAtLeast(100L))
        }
    }
}
