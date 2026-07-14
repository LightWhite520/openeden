package io.openeden.runtime.tick

import io.openeden.runtime.affect.OmegaAccumulationEngine
import io.openeden.runtime.affect.OmegaState
import io.openeden.runtime.affect.ShockState
import io.openeden.runtime.affect.ShockStateEngine
import io.openeden.runtime.inference.InferenceExecutor
import io.openeden.runtime.session.SessionStateStore
import io.openeden.runtime.state.RuntimeConfig
import io.openeden.runtime.state.VectorWriteResult
import io.openeden.runtime.state.VectorWriteService

import io.openeden.bio.BioVector
import io.openeden.trace.TraceTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Clock

data class RuntimeTickResult(
    val sessionId: String,
    val traceTags: Set<String>,
)

class RuntimeTickScheduler(
    private val store: SessionStateStore,
    private val writer: VectorWriteService,
    private val fluctuation: SineWaveFluctuationEngine,
    private val inferenceExecutor: InferenceExecutor,
    private val config: RuntimeConfig = RuntimeConfig.Default,
    private val startedAtMs: Long = Clock.System.now().toEpochMilliseconds(),
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    suspend fun evaluateOnce(nowMs: Long = this.nowMs()): List<RuntimeTickResult> {
        val results = mutableListOf<RuntimeTickResult>()
        for (sessionId in store.sessionIds()) {
            val result = runCatching {
                val elapsed = nowMs - startedAtMs
                val tickMath = inferenceExecutor.run {
                    val latest = store.read(sessionId)
                    val driftDelta = fluctuation.deltaAt(elapsed)
                    val driftedVector = latest.vector.apply(driftDelta)
                    val decayedShock = latest.shockState?.let { ShockStateEngine.decay(it, elapsed) }
                    val omega = OmegaAccumulationEngine.accumulate(
                        omega = latest.omega,
                        vector = driftedVector,
                        elapsedMillis = elapsed,
                        config = config.omega,
                    )
                    TickMath(
                        vector = driftedVector,
                        shockState = decayedShock,
                        omega = omega,
                        shockDecayed = decayedShock != latest.shockState,
                        omegaChanged = omega != latest.omega,
                    )
                }
                writer.applyRuntimeTick(sessionId) { latest ->
                    val traceTags = buildSet {
                        add(TraceTag.BackgroundDrift)
                        if (tickMath.shockDecayed) add(TraceTag.ShockStateDecayed)
                        if (tickMath.omegaChanged) add(TraceTag.OmegaAccumulated)
                    }
                    latest.copy(
                        vector = tickMath.vector,
                        shockState = tickMath.shockState,
                        omega = tickMath.omega,
                    ) to traceTags
                }
            }.getOrElse {
                VectorWriteResult(
                    state = store.read(sessionId),
                    traceTags = setOf(TraceTag.RuntimeTickSessionFailed),
                )
            }
            results += RuntimeTickResult(sessionId, result.traceTags)
        }
        return results
    }

    fun start(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            delay(config.tick.intervalMs)
            evaluateOnce()
        }
    }
}

private data class TickMath(
    val vector: BioVector,
    val shockState: ShockState?,
    val omega: OmegaState,
    val shockDecayed: Boolean,
    val omegaChanged: Boolean,
)
