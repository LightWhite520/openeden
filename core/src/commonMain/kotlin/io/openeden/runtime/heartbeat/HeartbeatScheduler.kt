package io.openeden.runtime.heartbeat

import io.openeden.runtime.affect.ShockState

import io.openeden.prompt.HEARTBEAT_SHOCK_TRIGGER
import io.openeden.prompt.HEARTBEAT_TRIGGER
import io.openeden.runtime.pipeline.DevelopmentMessagePipeline
import io.openeden.runtime.pipeline.DevelopmentMessageRequest
import io.openeden.runtime.pipeline.TurnSource
import io.openeden.runtime.incarnation.IncarnationStateStore
import io.openeden.runtime.session.SessionState
import io.openeden.runtime.session.SessionStateStore
import io.openeden.runtime.state.VectorWriteService
import io.openeden.runtime.time.RuntimeClock
import io.openeden.runtime.time.SystemRuntimeClock
import io.openeden.transcript.TranscriptStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class HeartbeatScheduler(
    private val pipeline: DevelopmentMessagePipeline,
    private val store: SessionStateStore,
    private val writer: VectorWriteService,
    private val delivery: HeartbeatDelivery = NoopHeartbeatDelivery,
    private val config: HeartbeatConfig = HeartbeatConfig(),
    private val interval: HeartbeatIntervalStrategy = RandomHeartbeatInterval(),
    private val routeResolver: HeartbeatRouteResolver = OwnerHeartbeatRouteResolver(owner = null),
    private val clock: RuntimeClock = SystemRuntimeClock,
    private val onDeliveryDropped: (String, HeartbeatTarget, Exception) -> Unit = { _, _, _ -> },
    private val incarnationStore: IncarnationStateStore? = null,
    private val transcriptStore: TranscriptStore? = null,
) {
    fun decide(state: SessionState, now: Long): HeartbeatDecision {
        return decide(state.lastUserActivityMs, state.shockState, now)
    }

    private fun decide(lastUserActivityMs: Long?, shock: ShockState?, now: Long): HeartbeatDecision {
        val silenceMs = lastUserActivityMs?.let { now - it } ?: Long.MAX_VALUE
        if (shock != null && shock.active && shock.intensity >= config.shockIntensityGate &&
            !shock.shockHeartbeatFired && silenceMs >= config.shockSilenceGateMs
        ) {
            return HeartbeatDecision.SHOCK
        }
        if (silenceMs >= config.baseSilenceGateMs) return HeartbeatDecision.BASE
        return HeartbeatDecision.SKIP
    }

    suspend fun evaluateOnce(now: Long = clock.nowMs()) {
        val activeIncarnationId = transcriptStore?.activeIncarnation()?.id
        val activeIncarnation = activeIncarnationId?.let { incarnationStore?.read(it) }
        val processedIncarnations = mutableSetOf<String>()
        for (sessionId in store.sessionIds()) {
            val scopeState = store.read(sessionId)
            val decision = if (activeIncarnation != null) {
                decide(scopeState.lastUserActivityMs, activeIncarnation.shockState, now)
            } else {
                decide(scopeState, now)
            }
            if (decision == HeartbeatDecision.SKIP) continue
            if (activeIncarnationId != null && !processedIncarnations.add(activeIncarnationId)) continue
            val shock = decision == HeartbeatDecision.SHOCK
            val platform = sessionId.substringBefore(':')
            val scopeId = sessionId.substringAfter(':')
            val result = pipeline.handle(
                DevelopmentMessageRequest(
                    turnId = "$sessionId:heartbeat:$now:${decision.name}",
                    platform = platform,
                    scopeId = scopeId,
                    userId = HEARTBEAT_USER_ID,
                    text = if (shock) HEARTBEAT_SHOCK_TRIGGER else HEARTBEAT_TRIGGER,
                    emotionConfidence = 0.0f,
                    source = TurnSource.HEARTBEAT,
                ),
            )
            if (shock) {
                if (activeIncarnationId != null && incarnationStore != null) {
                    writer.markShockHeartbeatFiredForIncarnation(activeIncarnationId)
                } else {
                    writer.markShockHeartbeatFired(sessionId)
                }
            }
            for (target in routeResolver.targetsFor(sessionId, now)) {
                try {
                    if (!delivery.isConnected(target)) continue
                    delivery.deliver(sessionId, target, shock, result.response)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    try {
                        onDeliveryDropped(sessionId, target, failure)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Delivery-drop observation is best-effort and must not stop later heartbeats.
                    }
                }
            }
        }
    }

    fun start(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            delay(interval.nextDelayMs())
            evaluateOnce()
        }
    }

    companion object {
        const val HEARTBEAT_USER_ID = "INTERNAL"
    }
}
