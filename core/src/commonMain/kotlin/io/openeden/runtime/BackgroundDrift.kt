package io.openeden.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Clock

class BackgroundDriftScheduler(
    private val store: SessionStateStore,
    private val writer: VectorWriteService,
    private val fluctuation: SineWaveFluctuationEngine,
    private val intervalMs: Long = 60_000L,
    private val startedAtMs: Long = Clock.System.now().toEpochMilliseconds(),
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    init {
        require(intervalMs > 0)
    }

    suspend fun evaluateOnce(nowMs: Long = this.nowMs()) {
        val elapsed = nowMs - startedAtMs
        val delta = fluctuation.deltaAt(elapsed)
        for (sessionId in store.sessionIds()) {
            writer.applyBackgroundDrift(sessionId, delta)
        }
    }

    fun start(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            delay(intervalMs)
            evaluateOnce()
        }
    }
}
