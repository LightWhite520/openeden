package io.openeden.runtime.heartbeat

import io.openeden.runtime.affect.ShockState
import io.openeden.runtime.incarnation.MutableIncarnationStateStore
import io.openeden.runtime.pipeline.DevelopmentMessagePipeline
import io.openeden.runtime.pipeline.DevelopmentMessageRequest
import io.openeden.runtime.session.MutableSessionStateStore
import io.openeden.runtime.session.SessionStateStore
import io.openeden.runtime.state.VectorWriteService
import io.openeden.runtime.time.MutableRuntimeClock
import io.openeden.runtime.time.RuntimeClock
import io.openeden.transcript.InMemoryTranscriptStore


import io.openeden.bio.BioVector
import io.openeden.llm.LlmClient
import io.openeden.llm.LlmOutput
import io.openeden.persona.PersonaConfig
import io.openeden.persona.PersonaMode
import io.openeden.persona.PersonaSubState
import io.openeden.prompt.BuiltPrompt
import io.openeden.prompt.PromptSectionKeys
import io.openeden.trace.TraceTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
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
    fun `default evaluation reads the injected runtime clock`() = runTest {
        val clock = MutableRuntimeClock(now)
        val store = MutableSessionStateStore()
        store.write(neutral("QQ:silent").copy(lastUserActivityMs = sixMinAgo))
        val delivery = RecordingDelivery()

        scheduler(store, delivery, clock = clock).evaluateOnce()

        assertEquals(listOf("QQ:silent"), delivery.calls.map { it.sessionId })
    }

    @Test
    fun `one scheduled heartbeat advances a shared incarnation once`() = runTest {
        val transcript = InMemoryTranscriptStore("incarnation-a")
        val store = MutableSessionStateStore(transcriptStore = transcript)
        val incarnationStore = MutableIncarnationStateStore(transcriptStore = transcript)
        incarnationStore.readOrCreate(
            incarnationId = "incarnation-a",
            personaMode = PersonaMode.GROWTH,
            personaStartSubState = PersonaSubState.PRE_COMMAND,
        )
        store.write(neutral("QQ:group-a").copy(lastUserActivityMs = sixMinAgo))
        store.write(neutral("WEB:user-a").copy(lastUserActivityMs = sixMinAgo))
        val writer = VectorWriteService(incarnationStore = incarnationStore)
        val delivery = RecordingDelivery()
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = personaConfig(),
            llmClient = validLlm(),
            store = store,
            incarnationStateStore = incarnationStore,
            vectorWriteService = writer,
            transcriptStore = transcript,
            clock = MutableRuntimeClock(now),
        )
        val scheduler = HeartbeatScheduler(
            pipeline = pipeline,
            store = store,
            writer = writer,
            delivery = delivery,
            incarnationStore = incarnationStore,
            transcriptStore = transcript,
            clock = MutableRuntimeClock(now),
        )

        scheduler.evaluateOnce(now)

        assertEquals(1L, incarnationStore.read("incarnation-a").evolutionIndex)
        assertEquals(1, delivery.calls.size)
        assertEquals("owner", delivery.calls.single().userId)
    }

    @Test
    fun `user activity suppresses that scope while an idle scope advances the shared incarnation`() = runTest {
        val transcript = InMemoryTranscriptStore("incarnation-a")
        val store = MutableSessionStateStore(transcriptStore = transcript)
        val incarnationStore = MutableIncarnationStateStore(transcriptStore = transcript)
        incarnationStore.readOrCreate(
            incarnationId = "incarnation-a",
            personaMode = PersonaMode.GROWTH,
            personaStartSubState = PersonaSubState.PRE_COMMAND,
        )
        val writer = VectorWriteService(incarnationStore = incarnationStore)
        val clock = MutableRuntimeClock(now)
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = personaConfig(),
            llmClient = validLlm(),
            store = store,
            incarnationStateStore = incarnationStore,
            vectorWriteService = writer,
            transcriptStore = transcript,
            clock = clock,
        )
        pipeline.handle(
            DevelopmentMessageRequest(
                turnId = "user-turn",
                platform = "QQ",
                scopeId = "active",
                userId = "user",
                text = "hello",
                emotionConfidence = 0.49f,
            ),
        )
        store.write(neutral("WEB:idle").copy(lastUserActivityMs = sixMinAgo))
        val delivery = RecordingDelivery()
        val scheduler = HeartbeatScheduler(
            pipeline = pipeline,
            store = store,
            writer = writer,
            delivery = delivery,
            incarnationStore = incarnationStore,
            transcriptStore = transcript,
            clock = clock,
        )

        scheduler.evaluateOnce(now)

        assertEquals(now, store.read("QQ:active").lastUserActivityMs)
        assertEquals(listOf("WEB:idle"), delivery.calls.map { it.sessionId })
        assertEquals(2L, incarnationStore.read("incarnation-a").evolutionIndex)
    }

    @Test
    fun `rejected user output still suppresses the next heartbeat`() = runTest {
        val transcript = InMemoryTranscriptStore("incarnation-a")
        val store = MutableSessionStateStore(transcriptStore = transcript)
        val incarnationStore = MutableIncarnationStateStore(transcriptStore = transcript)
        incarnationStore.readOrCreate(
            incarnationId = "incarnation-a",
            personaMode = PersonaMode.GROWTH,
            personaStartSubState = PersonaSubState.PRE_COMMAND,
        )
        val writer = VectorWriteService(incarnationStore = incarnationStore)
        val clock = MutableRuntimeClock(now)
        val rejectedLlm = object : LlmClient {
            override suspend fun complete(prompt: BuiltPrompt): LlmOutput = LlmOutput(
                internalLogic = "",
                vectorDelta = emptyMap(),
                response = "",
            )
        }
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = personaConfig(),
            llmClient = rejectedLlm,
            store = store,
            incarnationStateStore = incarnationStore,
            vectorWriteService = writer,
            transcriptStore = transcript,
            clock = clock,
        )
        pipeline.handle(
            DevelopmentMessageRequest(
                turnId = "rejected-user-turn",
                platform = "QQ",
                scopeId = "active",
                userId = "user",
                text = "hello",
                emotionConfidence = 0.49f,
            ),
        )
        val delivery = RecordingDelivery()
        val scheduler = HeartbeatScheduler(
            pipeline = pipeline,
            store = store,
            writer = writer,
            delivery = delivery,
            incarnationStore = incarnationStore,
            transcriptStore = transcript,
            clock = clock,
        )

        scheduler.evaluateOnce(now)

        assertEquals(now, store.read("QQ:active").lastUserActivityMs)
        assertEquals(emptyList(), delivery.calls)
        assertEquals(0L, incarnationStore.read("incarnation-a").evolutionIndex)
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
    fun `heartbeat delivers only to configured owner`() = runTest {
        val store = MutableSessionStateStore()
        store.write(neutral("QQ:shared").copy(lastUserActivityMs = sixMinAgo))
        val delivery = RecordingDelivery()
        val router = OwnerHeartbeatRouteResolver(owner = HeartbeatOwner(platform = "QQ", userId = "owner"))

        scheduler(store, delivery, routeResolver = router).evaluateOnce(now)

        assertEquals(listOf("QQ"), delivery.calls.map { it.platform })
        assertEquals(listOf("owner"), delivery.calls.map { it.userId })
    }

    @Test
    fun `heartbeat without owner updates state but drops delivery`() = runTest {
        val store = MutableSessionStateStore()
        store.write(neutral("QQ:shared").copy(lastUserActivityMs = sixMinAgo))
        val delivery = RecordingDelivery()
        val router = OwnerHeartbeatRouteResolver(owner = null)

        scheduler(store, delivery, routeResolver = router).evaluateOnce(now)

        assertEquals(emptyList(), delivery.calls)
        assertEquals(1, store.read("QQ:shared").evolutionIndex)
    }

    @Test
    fun `disconnected owner is skipped after heartbeat state evolves`() = runTest {
        val store = MutableSessionStateStore()
        store.write(neutral("QQ:shared").copy(lastUserActivityMs = sixMinAgo))
        val delivery = RecordingDelivery(connected = false)

        scheduler(store, delivery).evaluateOnce(now)

        assertEquals(1, delivery.connectionChecks)
        assertEquals(emptyList(), delivery.calls)
        assertEquals(1, store.read("QQ:shared").evolutionIndex)
    }

    @Test
    fun `ordinary send failure is dropped after state evolution and later sessions continue`() = runTest {
        val store = MutableSessionStateStore()
        store.write(
            neutral("QQ:failing").copy(
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
        store.write(neutral("QQ:succeeding").copy(lastUserActivityMs = sixMinAgo))
        val successfulSessions = mutableListOf<String>()
        val delivery = object : HeartbeatDelivery {
            override fun isConnected(target: HeartbeatTarget): Boolean = true

            override suspend fun deliver(
                sessionId: String,
                target: HeartbeatTarget,
                shock: Boolean,
                response: String?,
            ) {
                if (sessionId == "QQ:failing") error("send failed")
                successfulSessions += sessionId
            }
        }

        scheduler(store, delivery).evaluateOnce(now)

        assertEquals(1, store.read("QQ:failing").evolutionIndex)
        assertTrue(store.read("QQ:failing").shockState!!.shockHeartbeatFired)
        assertEquals(1, store.read("QQ:succeeding").evolutionIndex)
        assertEquals(listOf("QQ:succeeding"), successfulSessions)
    }

    @Test
    fun `connection probe failure is isolated after state evolution and later target continues`() = runTest {
        val store = MutableSessionStateStore()
        store.write(neutral("QQ:shared").copy(lastUserActivityMs = sixMinAgo))
        val deliveredUsers = mutableListOf<String>()
        val delivery = object : HeartbeatDelivery {
            override fun isConnected(target: HeartbeatTarget): Boolean {
                if (target.userId == "failing") error("probe failed")
                return true
            }

            override suspend fun deliver(
                sessionId: String,
                target: HeartbeatTarget,
                shock: Boolean,
                response: String?,
            ) {
                deliveredUsers += target.userId
            }
        }
        val router = HeartbeatRouteResolver { _, _ ->
            listOf(HeartbeatTarget("QQ", "failing"), HeartbeatTarget("QQ", "succeeding"))
        }

        scheduler(store, delivery, routeResolver = router).evaluateOnce(now)

        assertEquals(1, store.read("QQ:shared").evolutionIndex)
        assertEquals(listOf("succeeding"), deliveredUsers)
    }

    @Test
    fun `send failure for first target does not prevent delivery to second target`() = runTest {
        val store = MutableSessionStateStore()
        store.write(neutral("QQ:shared").copy(lastUserActivityMs = sixMinAgo))
        val deliveredUsers = mutableListOf<String>()
        val failure = IllegalStateException("send failed")
        var dropped: Triple<String, HeartbeatTarget, Exception>? = null
        val delivery = object : HeartbeatDelivery {
            override fun isConnected(target: HeartbeatTarget): Boolean = true

            override suspend fun deliver(
                sessionId: String,
                target: HeartbeatTarget,
                shock: Boolean,
                response: String?,
            ) {
                if (target.userId == "failing") throw failure
                deliveredUsers += target.userId
            }
        }
        val router = HeartbeatRouteResolver { _, _ ->
            listOf(HeartbeatTarget("QQ", "failing"), HeartbeatTarget("QQ", "succeeding"))
        }

        scheduler(
            store = store,
            delivery = delivery,
            routeResolver = router,
            onDeliveryDropped = { sessionId, target, cause -> dropped = Triple(sessionId, target, cause) },
        ).evaluateOnce(now)

        assertEquals(listOf("succeeding"), deliveredUsers)
        assertEquals(Triple("QQ:shared", HeartbeatTarget("QQ", "failing"), failure), dropped)
    }

    @Test
    fun `ordinary drop callback failure does not prevent delivery to second target`() = runTest {
        val store = MutableSessionStateStore()
        store.write(neutral("QQ:shared").copy(lastUserActivityMs = sixMinAgo))
        val deliveredUsers = mutableListOf<String>()
        val delivery = object : HeartbeatDelivery {
            override fun isConnected(target: HeartbeatTarget): Boolean = true

            override suspend fun deliver(
                sessionId: String,
                target: HeartbeatTarget,
                shock: Boolean,
                response: String?,
            ) {
                if (target.userId == "failing") error("send failed")
                deliveredUsers += target.userId
            }
        }
        val router = HeartbeatRouteResolver { _, _ ->
            listOf(HeartbeatTarget("QQ", "failing"), HeartbeatTarget("QQ", "succeeding"))
        }

        scheduler(
            store = store,
            delivery = delivery,
            routeResolver = router,
            onDeliveryDropped = { _, _, _ -> error("observer failed") },
        ).evaluateOnce(now)

        assertEquals(listOf("succeeding"), deliveredUsers)
    }

    @Test
    fun `drop callback cancellation propagates`() = runTest {
        val store = MutableSessionStateStore()
        store.write(neutral("QQ:shared").copy(lastUserActivityMs = sixMinAgo))
        val delivery = object : HeartbeatDelivery {
            override fun isConnected(target: HeartbeatTarget): Boolean = true

            override suspend fun deliver(
                sessionId: String,
                target: HeartbeatTarget,
                shock: Boolean,
                response: String?,
            ) {
                error("send failed")
            }
        }

        assertFailsWith<CancellationException> {
            scheduler(
                store = store,
                delivery = delivery,
                onDeliveryDropped = { _, _, _ -> throw CancellationException("cancelled") },
            ).evaluateOnce(now)
        }
    }

    @Test
    fun `delivery cancellation propagates after heartbeat state evolves`() = runTest {
        val store = MutableSessionStateStore()
        store.write(neutral("QQ:shared").copy(lastUserActivityMs = sixMinAgo))
        val delivery = object : HeartbeatDelivery {
            override fun isConnected(target: HeartbeatTarget): Boolean = true

            override suspend fun deliver(
                sessionId: String,
                target: HeartbeatTarget,
                shock: Boolean,
                response: String?,
            ) {
                throw CancellationException("cancelled")
            }
        }

        assertFailsWith<CancellationException> {
            scheduler(store, delivery).evaluateOnce(now)
        }
        assertEquals(1, store.read("QQ:shared").evolutionIndex)
    }

    private fun neutral(id: String) = SessionStateStore.neutral(id)

    private fun scheduler(
        store: MutableSessionStateStore,
        delivery: HeartbeatDelivery,
        routeResolver: HeartbeatRouteResolver = OwnerHeartbeatRouteResolver(HeartbeatOwner("QQ", "owner")),
        clock: RuntimeClock = MutableRuntimeClock(now),
        onDeliveryDropped: (String, HeartbeatTarget, Exception) -> Unit = { _, _, _ -> },
    ): HeartbeatScheduler {
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = personaConfig(),
            llmClient = validLlm(),
            store = store,
            clock = clock,
        )
        return HeartbeatScheduler(
            pipeline = pipeline,
            store = store,
            writer = VectorWriteService(store),
            delivery = delivery,
            routeResolver = routeResolver,
            clock = clock,
            onDeliveryDropped = onDeliveryDropped,
        )
    }

    private fun validLlm(): LlmClient = object : LlmClient {
        override suspend fun complete(prompt: BuiltPrompt): LlmOutput = LlmOutput(
            internalLogic = "logic references HEURISTIC_FALLBACK",
            vectorDelta = mapOf(
                "L" to 0.0f, "P" to 0.1f, "E" to 0.0f, "S" to 0.0f,
                "tau" to 0.0f, "V" to 0.0f, "M" to 0.0f, "F" to 0.0f,
            ),
            response = "hb",
        )
    }

    private fun personaConfig(): PersonaConfig = PersonaConfig(
        mode = PersonaMode.GROWTH,
        startSubState = PersonaSubState.PRE_COMMAND,
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

private class RecordingDelivery(
    private val connected: Boolean = true,
) : HeartbeatDelivery {
    data class Call(val sessionId: String, val platform: String, val userId: String, val shock: Boolean, val response: String?)

    val calls = mutableListOf<Call>()
    var connectionChecks = 0

    override fun isConnected(target: HeartbeatTarget): Boolean {
        connectionChecks += 1
        return connected
    }

    override suspend fun deliver(sessionId: String, target: HeartbeatTarget, shock: Boolean, response: String?) {
        calls += Call(sessionId, target.platform, target.userId, shock, response)
    }
}
