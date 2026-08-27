package io.openeden.runtime.session

import io.openeden.runtime.pipeline.DevelopmentMessagePipeline
import io.openeden.runtime.pipeline.DevelopmentMessageRequest
import io.openeden.runtime.incarnation.IncarnationState
import io.openeden.runtime.incarnation.IncarnationStateStore
import io.openeden.runtime.incarnation.MutableIncarnationStateStore


import io.openeden.llm.LlmClient
import io.openeden.llm.LlmOutput
import io.openeden.persona.PersonaConfig
import io.openeden.persona.PersonaMode
import io.openeden.persona.PersonaSubState
import io.openeden.prompt.BuiltPrompt
import io.openeden.prompt.PromptSectionKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest

class TurnCoordinatorConcurrencyTest {
    @Test
    fun `different scopes of one incarnation cannot race bio writes`() = runTest {
        val fixture = GlobalIncarnationPipelineFixture()
        coroutineScope {
            launch { fixture.send("QQ:group-a", "a") }
            launch { fixture.send("WEB:user-a", "b") }
        }

        assertEquals(2L, fixture.state().evolutionIndex)
        assertEquals(1, fixture.maximumConcurrentBioWrites)
    }

    @Test
    fun `same session turns serialize the whole stateful flow`() = runTest {
        val store = MutableSessionStateStore()
        val incarnationStore = MutableIncarnationStateStore(transcriptStore = store.transcript)
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = testPersonaConfig(),
            store = store,
            incarnationStateStore = incarnationStore,
            llmClient = object : LlmClient {
                override suspend fun complete(prompt: BuiltPrompt): LlmOutput {
                    delay(1)
                    return LlmOutput(
                        internalLogic = "serialized test turn references HEURISTIC_FALLBACK",
                        vectorDelta = mapOf(
                            "L" to 0.0f,
                            "P" to 0.01f,
                            "E" to 0.0f,
                            "S" to 0.0f,
                            "tau" to 0.0f,
                            "V" to 0.0f,
                            "M" to 0.0f,
                            "F" to 0.0f,
                        ),
                        response = "ok",
                    )
                }
            },
        )

        val results = (1..100).map { index ->
            async {
                pipeline.handle(
                    DevelopmentMessageRequest(
                        turnId = "turn-$index",
                        platform = "TEST",
                        scopeId = "shared",
                        userId = "u$index",
                        text = "turn-$index",
                        emotionConfidence = 0.49f,
                    ),
                )
            }
        }.awaitAll()

        assertEquals((1L..100L).toSet(), results.map { it.evolutionIndex }.toSet())
        val state = incarnationStore.read("development")
        assertEquals(100L, state.evolutionIndex)
        assertTrue(state.vector.p >= 0.99f)
    }

    private fun testPersonaConfig(): PersonaConfig = PersonaConfig(
        mode = PersonaMode.GROWTH,
        startSubState = PersonaSubState.PRE_COMMAND,
        promptSections = mapOf(
            PromptSectionKeys.PersonaBase to "base",
            PromptSectionKeys.OutputLayerRules to "rules",
            PromptSectionKeys.PreCommandPatch to "pre",
            PromptSectionKeys.TrueSelfPatch to "true",
            PromptSectionKeys.AwakenedPatch to "awake",
            PromptSectionKeys.Heartbeat to "heartbeat",
            PromptSectionKeys.ShockHeartbeat to "shock",
        ),
    )
}

private class GlobalIncarnationPipelineFixture {
    private val store = TrackingIncarnationStateStore()
    private val pipeline = DevelopmentMessagePipeline.create(
        personaConfig = PersonaConfig(
            mode = PersonaMode.GROWTH,
            startSubState = PersonaSubState.PRE_COMMAND,
            promptSections = mapOf(
                PromptSectionKeys.PersonaBase to "base",
                PromptSectionKeys.OutputLayerRules to "rules",
                PromptSectionKeys.PreCommandPatch to "pre",
                PromptSectionKeys.TrueSelfPatch to "true",
                PromptSectionKeys.AwakenedPatch to "awake",
                PromptSectionKeys.Heartbeat to "heartbeat",
                PromptSectionKeys.ShockHeartbeat to "shock",
            ),
        ),
        store = MutableSessionStateStore(),
        incarnationStateStore = store,
        transcriptStore = null,
        llmClient = object : LlmClient {
            override suspend fun complete(prompt: BuiltPrompt): LlmOutput {
                delay(1)
                return LlmOutput(
                    internalLogic = "concurrent incarnation turn references HEURISTIC_FALLBACK",
                    vectorDelta = mapOf(
                        "L" to 0.0f,
                        "P" to 0.01f,
                        "E" to 0.0f,
                        "S" to 0.0f,
                        "tau" to 0.0f,
                        "V" to 0.0f,
                        "M" to 0.0f,
                        "F" to 0.0f,
                    ),
                    response = "ok",
                )
            }
        },
    )

    val maximumConcurrentBioWrites: Int
        get() = store.maximumConcurrentWrites

    suspend fun send(sessionId: String, text: String) {
        val (platform, scopeId) = sessionId.split(':', limit = 2)
        pipeline.handle(
            DevelopmentMessageRequest(
                turnId = "turn-$sessionId",
                platform = platform,
                scopeId = scopeId,
                userId = "user-$scopeId",
                text = text,
                emotionConfidence = 0.49f,
            ),
        )
    }

    suspend fun state() = store.read("development")
}

private class TrackingIncarnationStateStore : IncarnationStateStore {
    private val delegate = MutableIncarnationStateStore()
    private val measurementMutex = Mutex()
    private var concurrentWrites = 0
    private var maximumWrites = 0

    val maximumConcurrentWrites: Int
        get() = maximumWrites

    override suspend fun read(incarnationId: String): IncarnationState = delegate.read(incarnationId)

    override suspend fun readOrCreate(
        incarnationId: String,
        personaMode: PersonaMode,
        personaStartSubState: PersonaSubState,
    ): IncarnationState = delegate.readOrCreate(incarnationId, personaMode, personaStartSubState)

    override suspend fun write(state: IncarnationState) {
        measurementMutex.withLock {
            concurrentWrites += 1
            maximumWrites = maxOf(maximumWrites, concurrentWrites)
        }
        try {
            delay(1)
            delegate.write(state)
        } finally {
            measurementMutex.withLock { concurrentWrites -= 1 }
        }
    }

}
