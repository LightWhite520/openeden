package io.openeden.runtime.heartbeat

import io.openeden.runtime.affect.ShockState

import io.openeden.prompt.HEARTBEAT_SHOCK_TRIGGER
import io.openeden.prompt.HEARTBEAT_TRIGGER
import io.openeden.runtime.pipeline.DevelopmentMessagePipeline
import io.openeden.runtime.pipeline.DevelopmentMessageRequest
import io.openeden.runtime.pipeline.TurnSource
import io.openeden.runtime.session.SessionState
import io.openeden.runtime.session.SessionStateStore
import io.openeden.runtime.state.VectorWriteService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Clock

class HeartbeatScheduler(
    private val pipeline: DevelopmentMessagePipeline,
    private val store: SessionStateStore,
    private val writer: VectorWriteService,
    private val delivery: HeartbeatDelivery = NoopHeartbeatDelivery,
    private val config: HeartbeatConfig = HeartbeatConfig(),
    private val interval: HeartbeatIntervalStrategy = RandomHeartbeatInterval(),
    private val routeResolver: HeartbeatRouteResolver = OwnerHeartbeatRouteResolver(owner = null),
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    fun decide(state: SessionState, now: Long): HeartbeatDecision {
        val silenceMs = state.lastUserActivityMs?.let { now - it } ?: Long.MAX_VALUE
        val shock = state.shockState
        if (shock != null && shock.active && shock.intensity >= config.shockIntensityGate &&
            !shock.shockHeartbeatFired && silenceMs >= config.shockSilenceGateMs
        ) {
            return HeartbeatDecision.SHOCK
        }
        if (silenceMs >= config.baseSilenceGateMs) return HeartbeatDecision.BASE
        return HeartbeatDecision.SKIP
    }

    suspend fun evaluateOnce(now: Long = nowMs()) {
        for (sessionId in store.sessionIds()) {
            val decision = decide(store.read(sessionId), now)
            if (decision == HeartbeatDecision.SKIP) continue
            val shock = decision == HeartbeatDecision.SHOCK
            val platform = sessionId.substringBefore(':')
            val scopeId = sessionId.substringAfter(':')
            val result = pipeline.handle(
                DevelopmentMessageRequest(
                    platform = platform,
                    scopeId = scopeId,
                    userId = HEARTBEAT_USER_ID,
                    text = if (shock) HEARTBEAT_SHOCK_TRIGGER else HEARTBEAT_TRIGGER,
                    emotionConfidence = 0.0f,
                    source = TurnSource.HEARTBEAT,
                ),
            )
            if (shock) writer.markShockHeartbeatFired(sessionId)
            for (target in routeResolver.targetsFor(sessionId, now)) {
                delivery.deliver(sessionId, target, shock, result.response)
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
