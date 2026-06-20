package io.openeden.runtime

import io.openeden.prompt.HEARTBEAT_SHOCK_TRIGGER
import io.openeden.prompt.HEARTBEAT_TRIGGER
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.time.Clock

/**
 * The Heartbeat scheduler (AGENTS.md §9.3) — ATRI's proactive presence. It periodically wakes,
 * and for each live session decides whether to speak unprompted, routing a heartbeat turn through
 * the full pipeline so the 8D vector and evolution_index advance like any normal turn.
 *
 * Delivery is abstracted behind [HeartbeatDelivery] because no real platform adapter exists yet;
 * [HeartbeatRouteResolver] implements the §13.4 routing decision from adapter activity records.
 */

/** Where a generated heartbeat goes. Default impls log or drop; a real adapter replaces this. */
interface HeartbeatDelivery {
    suspend fun deliver(sessionId: String, platform: String, shock: Boolean, response: String?)
}

object NoopHeartbeatDelivery : HeartbeatDelivery {
    override suspend fun deliver(sessionId: String, platform: String, shock: Boolean, response: String?) {}
}

/** Logs each heartbeat through an injectable sink (no logging framework in commonMain). */
class LoggingHeartbeatDelivery(private val sink: (String) -> Unit = ::println) : HeartbeatDelivery {
    override suspend fun deliver(sessionId: String, platform: String, shock: Boolean, response: String?) {
        val kind = if (shock) "shock" else "base"
        sink("[heartbeat:$kind] $sessionId -> ${response ?: "<no response>"}")
    }
}

fun interface HeartbeatRouteResolver {
    fun platformsFor(sessionId: String, nowMs: Long): List<String>
}

object SessionIdHeartbeatRouteResolver : HeartbeatRouteResolver {
    override fun platformsFor(sessionId: String, nowMs: Long): List<String> =
        listOf(sessionId.substringBefore(':'))
}

data class AdapterActivity(
    val platform: String,
    val lastActiveMs: Long,
    val connected: Boolean = true,
)

class AdapterHeartbeatRouteResolver(
    private val recentWindowMs: Long = 2 * 60 * 60_000L,
) : HeartbeatRouteResolver {
    private val activityBySession = mutableMapOf<String, MutableMap<String, AdapterActivity>>()

    fun recordAdapterActivity(
        sessionId: String,
        platform: String,
        lastActiveMs: Long,
        connected: Boolean = true,
    ) {
        val sessionActivity = activityBySession.getOrPut(sessionId) { mutableMapOf() }
        sessionActivity[platform] = AdapterActivity(platform, lastActiveMs, connected)
    }

    override fun platformsFor(sessionId: String, nowMs: Long): List<String> {
        val connected = activityBySession[sessionId]
            ?.values
            ?.filter { it.connected }
            ?.sortedByDescending { it.lastActiveMs }
            .orEmpty()
        if (connected.isEmpty()) return SessionIdHeartbeatRouteResolver.platformsFor(sessionId, nowMs)

        val latest = connected.first()
        return if (nowMs - latest.lastActiveMs <= recentWindowMs) {
            listOf(latest.platform)
        } else {
            connected.map { it.platform }.distinct()
        }
    }
}

/** Pluggable inter-fire delay so production randomizes while tests stay deterministic. */
fun interface HeartbeatIntervalStrategy {
    fun nextDelayMs(): Long
}

/** Uniform random draw in [minMs, maxMs], re-rolled each call (§9.3.1: re-randomize after each firing). */
class RandomHeartbeatInterval(
    private val minMs: Long = 5 * 60_000L,
    private val maxMs: Long = 4 * 60 * 60_000L,
    private val random: Random = Random.Default,
) : HeartbeatIntervalStrategy {
    override fun nextDelayMs(): Long = random.nextLong(minMs, maxMs + 1)
}

data class HeartbeatConfig(
    val baseSilenceGateMs: Long = 5 * 60_000L, // §9.3.1: don't fire if user spoke within 5 min
    val shockSilenceGateMs: Long = 30 * 60_000L, // §9.3.2: shock-extended requires >= 30 min silence
    val shockIntensityGate: Float = 0.7f, // §9.3.2
)

enum class HeartbeatDecision { SKIP, BASE, SHOCK }

class HeartbeatScheduler(
    private val pipeline: DevelopmentMessagePipeline,
    private val store: SessionStateStore,
    private val writer: VectorWriteService,
    private val delivery: HeartbeatDelivery = NoopHeartbeatDelivery,
    private val config: HeartbeatConfig = HeartbeatConfig(),
    private val interval: HeartbeatIntervalStrategy = RandomHeartbeatInterval(),
    private val routeResolver: HeartbeatRouteResolver = SessionIdHeartbeatRouteResolver,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    /** Pure gate logic, separated for deterministic testing. */
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

    /** Evaluate every live session once and fire the heartbeats that pass their gates. */
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
            // Latch the one-shot flag so a ShockState activation fires exactly one shock heartbeat.
            if (shock) writer.markShockHeartbeatFired(sessionId)
            for (targetPlatform in routeResolver.platformsFor(sessionId, now)) {
                delivery.deliver(sessionId, targetPlatform, shock, result.response)
            }
        }
    }

    /**
     * Launch the scheduler loop. The caller MUST pass a scope backed by a dedicated dispatcher,
     * separate from the request-handling dispatcher (§9.3.3). The interval is re-randomized each
     * iteration so firings spread across [5 min, 4 h].
     */
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
