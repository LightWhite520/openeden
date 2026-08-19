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
    val critical: Boolean = false,
)

class RuntimeTickScheduler(
    private val store: SessionStateStore,
    private val writer: VectorWriteService,
    private val fluctuation: SineWaveFluctuationEngine,
    private val inferenceExecutor: InferenceExecutor,
    private val config: RuntimeConfig = RuntimeConfig.Default,
    private val startedAtMs: Long = Clock.System.now().toEpochMilliseconds(),
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val onOmegaCritical: suspend (sessionId: String) -> Unit = {},
) {
    suspend fun evaluateOnce(nowMs: Long = this.nowMs()): List<RuntimeTickResult> {
        val results = mutableListOf<RuntimeTickResult>()
        for (sessionId in store.sessionIds()) {
            val result = runCatching {
                writer.applyRuntimeTick(sessionId) { latest ->
                    val previousTickAtMs = latest.lastRuntimeTickAtMs
                    if (previousTickAtMs == null) {
                        return@applyRuntimeTick latest.copy(lastRuntimeTickAtMs = nowMs) to
                            setOf(TraceTag.RuntimeTickBaseline)
                    }

                    val elapsed = (nowMs - previousTickAtMs).coerceAtLeast(0L)
                    val previousElapsed = (previousTickAtMs - startedAtMs).coerceAtLeast(0L)
                    val currentElapsed = (nowMs - startedAtMs).coerceAtLeast(previousElapsed)
                    val tickMath = inferenceExecutor.run {
                        val driftDelta = fluctuation.deltaBetween(previousElapsed, currentElapsed)
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
                    val traceTags = buildSet {
                        add(TraceTag.BackgroundDrift)
                        if (tickMath.shockDecayed) add(TraceTag.ShockStateDecayed)
                        if (tickMath.omegaChanged) add(TraceTag.OmegaAccumulated)
                    }
                    latest.copy(
                        vector = tickMath.vector,
                        shockState = tickMath.shockState,
                        omega = tickMath.omega,
                        lastRuntimeTickAtMs = nowMs,
                    ) to traceTags
                }
            }.getOrElse {
                VectorWriteResult(
                    state = store.read(sessionId),
                    traceTags = setOf(TraceTag.RuntimeTickSessionFailed),
                )
            }
            val critical = result.state.omega.value >= config.omegaCriticalThreshold
            if (critical) runCatching { onOmegaCritical(sessionId) }
            val traceTags = if (critical) result.traceTags + TraceTag.OmegaCritical else result.traceTags
            results += RuntimeTickResult(sessionId, traceTags, critical)
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
