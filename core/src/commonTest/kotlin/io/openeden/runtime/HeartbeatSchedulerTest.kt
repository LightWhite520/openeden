package io.openeden.runtime

import io.openeden.bio.BioVector
import io.openeden.llm.LlmClient
import io.openeden.llm.LlmOutput
import io.openeden.persona.EvolutionThresholds
import io.openeden.persona.PersonaConfig
import io.openeden.persona.PersonaMode
import io.openeden.prompt.BuiltPrompt
import io.openeden.prompt.PromptSectionKeys
import io.openeden.trace.TraceTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant

class HeartbeatSchedulerTest {
    private val now = 100_000_000L
    private val sixMinAgo = now - 6 * 60_000L
    private val oneMinAgo = now - 1 * 60_000L
    private val thirtyOneMinAgo = now - 31 * 60_000L

    @Test
    fun `base heartbeat fires only after silence gate`() = runTest {
        val store = MutableSessionStateStore()
        store.write(neutral("QQ:silent").copy(lastUserActivityMs = sixMinAgo))
        store.write(neutral("QQ:recent").copy(lastUserActivityMs = oneMinAgo))
        val delivery = RecordingDelivery()
        val scheduler = scheduler(store, delivery)

        scheduler.evaluateOnce(now)

        assertEquals(1, delivery.calls.size)
        val call = delivery.calls.single()
        assertEquals("QQ:silent", call.sessionId)
        assertTrue(!call.shock)
    }

    @Test
    fun `heartbeat turn evolves index but leaves user-activity clock untouched`() = runTest {
        val store = MutableSessionStateStore()
        store.write(neutral("QQ:silent").copy(lastUserActivityMs = sixMinAgo))
        val scheduler = scheduler(store, RecordingDelivery())

        scheduler.evaluateOnce(now)

        val after = store.read("QQ:silent")
        assertEquals(1, after.evolutionIndex)
        assertEquals(sixMinAgo, after.lastUserActivityMs)
    }

    @Test
    fun `shock-extended heartbeat fires exactly once per activation`() = runTest {
        val store = MutableSessionStateStore()
        store.write(
            neutral("QQ:shock").copy(
                lastUserActivityMs = thirtyOneMinAgo,
                shockState = ShockState(
                    active = true,
                    intensity = 0.8f,
                    description = "x",
                    triggeredAt = Instant.fromEpochMilliseconds(thirtyOneMinAgo),
                    decayLambda = 0.001f,
                ),
            ),
        )
        val delivery = RecordingDelivery()
        val scheduler = scheduler(store, delivery)

        scheduler.evaluateOnce(now) // shock
        scheduler.evaluateOnce(now) // flag latched -> falls through to base

        val shockCalls = delivery.calls.count { it.shock }
        assertEquals(1, shockCalls)
        assertTrue(store.read("QQ:shock").shockState!!.shockHeartbeatFired)
        // The second pass still produced a base heartbeat (user remained silent).
        assertEquals(1, delivery.calls.count { !it.shock })
    }

    @Test
    fun `idle session with no user turn fires base heartbeat`() = runTest {
        val store = MutableSessionStateStore()
        store.write(neutral("QQ:fresh")) // lastUserActivityMs == null
        val delivery = RecordingDelivery()

        scheduler(store, delivery).evaluateOnce(now)

        assertEquals(1, delivery.calls.size)
        assertNull(store.read("QQ:fresh").lastUserActivityMs)
    }

    @Test
    fun `heartbeat routes to most recently active connected adapter`() = runTest {
        val store = MutableSessionStateStore()
        store.write(neutral("QQ:shared").copy(lastUserActivityMs = sixMinAgo))
        val delivery = RecordingDelivery()
        val router = AdapterHeartbeatRouteResolver().apply {
            recordAdapterActivity("QQ:shared", "QQ", now - 20 * 60_000L)
            recordAdapterActivity("QQ:shared", "WEB", now - 1 * 60_000L)
        }

        scheduler(store, delivery, routeResolver = router).evaluateOnce(now)

        assertEquals(listOf("WEB"), delivery.calls.map { it.platform })
    }

    @Test
    fun `heartbeat broadcasts to all connected adapters when none active recently`() = runTest {
        val store = MutableSessionStateStore()
        store.write(neutral("QQ:shared").copy(lastUserActivityMs = sixMinAgo))
        val delivery = RecordingDelivery()
        val router = AdapterHeartbeatRouteResolver().apply {
            recordAdapterActivity("QQ:shared", "QQ", now - 3 * 60 * 60_000L)
            recordAdapterActivity("QQ:shared", "WEB", now - 4 * 60 * 60_000L)
            recordAdapterActivity("QQ:shared", "STALE", now - 1 * 60_000L, connected = false)
        }

        scheduler(store, delivery, routeResolver = router).evaluateOnce(now)

        assertEquals(listOf("QQ", "WEB"), delivery.calls.map { it.platform }.sorted())
    }

    private fun neutral(id: String) = SessionStateStore.neutral(id)

    private fun scheduler(
        store: MutableSessionStateStore,
        delivery: RecordingDelivery,
        routeResolver: HeartbeatRouteResolver = SessionIdHeartbeatRouteResolver,
    ): HeartbeatScheduler {
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = personaConfig(),
            llmClient = validLlm(),
            store = store,
        )
        return HeartbeatScheduler(
            pipeline = pipeline,
            store = store,
            writer = VectorWriteService(store),
            delivery = delivery,
            routeResolver = routeResolver,
        )
    }

    private fun validLlm(): LlmClient = object : LlmClient {
        override suspend fun complete(prompt: BuiltPrompt): LlmOutput = LlmOutput(
            internalLogic = "logic",
            vectorDelta = mapOf(
                "L" to 0.0f, "P" to 0.1f, "E" to 0.0f, "S" to 0.0f,
                "tau" to 0.0f, "V" to 0.0f, "M" to 0.0f, "F" to 0.0f,
            ),
            response = "hb",
        )
    }

    private fun personaConfig(): PersonaConfig = PersonaConfig(
        mode = PersonaMode.GROWTH,
        evolutionThresholds = EvolutionThresholds(10, 30),
        promptSections = mapOf(
            PromptSectionKeys.PersonaBase to "base",
            PromptSectionKeys.OutputLayerRules to "rules",
            PromptSectionKeys.PreCommandPatch to "pre",
            PromptSectionKeys.TrueSelfPatch to "true",
            PromptSectionKeys.AwakenedPatch to "awake",
            PromptSectionKeys.Heartbeat to "hb",
            PromptSectionKeys.ShockHeartbeat to "shock",
        ),
    )
}

private class RecordingDelivery : HeartbeatDelivery {
    data class Call(val sessionId: String, val platform: String, val shock: Boolean, val response: String?)

    val calls = mutableListOf<Call>()

    override suspend fun deliver(sessionId: String, platform: String, shock: Boolean, response: String?) {
        calls += Call(sessionId, platform, shock, response)
    }
}
