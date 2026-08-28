package io.openeden.runtime.pipeline

import io.openeden.runtime.session.MutableSessionStateStore
import io.openeden.runtime.incarnation.MutableIncarnationStateStore


import io.openeden.bio.BioVector
import io.openeden.llm.LlmOutput
import io.openeden.llm.LlmClient
import io.openeden.memory.MemoryStore
import io.openeden.memory.RetrievalResult
import io.openeden.persona.PersonaConfig
import io.openeden.persona.PersonaMode
import io.openeden.persona.PersonaOutputPolicy
import io.openeden.persona.PersonaSubState
import io.openeden.prompt.BuiltPrompt
import io.openeden.prompt.PromptSectionKeys
import io.openeden.runtime.inference.RecordingInferenceExecutor
import io.openeden.trace.TraceTag
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class RuntimePipelineTest {
    @Test
    fun `local runtime rewrites a policy violating response once through create defaults`() = runTest {
        var completions = 0
        val pipeline = OpenEdenRuntimePipeline.local(
            personaConfig = testPersonaConfig().copy(
                outputPolicy = PersonaOutputPolicy(
                    prohibitedPublicPatterns = setOf("^收到[。！!\\s]*$"),
                ),
            ),
            llmClient = object : LlmClient {
                override suspend fun complete(prompt: BuiltPrompt): LlmOutput {
                    completions += 1
                    return LlmOutput(
                        internalLogic = "runtime rewrite test references HEURISTIC_FALLBACK",
                        vectorDelta = zeroDelta(),
                        response = if (completions == 1) "收到" else "我会认真处理这件事。",
                    )
                }
            },
        )

        val result = pipeline.handle(
            LocalRuntimeRequest(
                turnId = "runtime-rewrite-1",
                userId = "owner",
                text = "请帮我处理",
            ),
        )

        assertEquals(2, completions)
        assertEquals("我会认真处理这件事。", result.response)
        assertEquals(1, result.evolutionIndex)
    }

    @Test
    fun `local runtime request maps to CLI session and persists state`() = runTest {
        val store = MutableSessionStateStore()
        val incarnationStore = MutableIncarnationStateStore(transcriptStore = store.transcript)
        val pipeline = OpenEdenRuntimePipeline.local(
            personaConfig = testPersonaConfig(),
            store = store,
            incarnationStateStore = incarnationStore,
            llmClient = object : LlmClient {
                override suspend fun complete(prompt: BuiltPrompt): LlmOutput = LlmOutput(
                    internalLogic = "local runtime contract test references HEURISTIC_FALLBACK",
                    vectorDelta = mapOf(
                        "L" to 0.0f,
                        "P" to 0.1f,
                        "E" to 0.0f,
                        "S" to 0.0f,
                        "tau" to 0.0f,
                        "V" to 0.0f,
                        "M" to 0.0f,
                        "F" to 0.0f,
                    ),
                    response = "ok",
                )
            },
        )

        val result = pipeline.handle(
            LocalRuntimeRequest(
                turnId = "runtime-turn-1",
                userId = "owner",
                text = "hello",
                emotionConfidence = 0.49f,
            ),
        )

        assertEquals("CLI:owner", result.sessionId)
        assertEquals("ok", result.response)
        assertEquals(1, result.evolutionIndex)
        assertEquals(BioVector.Neutral.copy(p = 0.6f), incarnationStore.read("development").vector)
        assertContains(result.traceTags, TraceTag.CodebookHeuristicFallback)
    }

    @Test
    fun `later turn injects earlier turn into retrieved history`() = runTest {
        val prompts = mutableListOf<BuiltPrompt>()
        val pipeline = OpenEdenRuntimePipeline.local(
            personaConfig = testPersonaConfig(),
            llmClient = object : LlmClient {
                override suspend fun complete(prompt: BuiltPrompt): LlmOutput {
                    prompts += prompt
                    return LlmOutput(
                        internalLogic = "history test references HEURISTIC_FALLBACK",
                        vectorDelta = zeroDelta(),
                        response = if (prompts.size == 1) "first response" else "second response",
                    )
                }
            },
        )

        pipeline.handle(
            LocalRuntimeRequest(
                turnId = "runtime-memory-1",
                userId = "owner",
                text = "first question",
                emotionConfidence = 0.0f,
            ),
        )
        pipeline.handle(
            LocalRuntimeRequest(
                turnId = "runtime-memory-2",
                userId = "owner",
                text = "我刚刚说了什么",
                emotionConfidence = 0.0f,
            ),
        )

        assertEquals(2, prompts.size)
        assertTrue(prompts[1].contextText.contains("first question"))
        assertTrue(prompts[1].contextText.contains("first response"))
    }

    @Test
    fun `local runtime wires default vector writer to supplied inference executor`() = runTest {
        val executor = RecordingInferenceExecutor()
        val pipeline = OpenEdenRuntimePipeline.local(
            personaConfig = testPersonaConfig(),
            inferenceExecutor = executor,
            memoryStore = emptyMemoryStore(),
            llmClient = object : LlmClient {
                override suspend fun complete(prompt: BuiltPrompt): LlmOutput = LlmOutput(
                    internalLogic = "local runtime wiring test references HEURISTIC_FALLBACK",
                    vectorDelta = mapOf(
                        "L" to 0.0f,
                        "P" to 0.1f,
                        "E" to 0.0f,
                        "S" to 0.0f,
                        "tau" to 0.0f,
                        "V" to 0.0f,
                        "M" to 0.0f,
                        "F" to 0.0f,
                    ),
                    response = "ok",
                )
            },
        )

        pipeline.handle(
            LocalRuntimeRequest(
                turnId = "runtime-wiring-1",
                userId = "owner",
                text = "hello",
                emotionConfidence = 0.0f,
            ),
        )

        assertTrue(executor.calls >= 10, "inference calls=${executor.calls}")
    }

    private fun emptyMemoryStore(): MemoryStore = object : MemoryStore {
        override suspend fun write(entry: io.openeden.memory.MemoryEntry): Set<String> = emptySet()

        override suspend fun retrieve(request: io.openeden.memory.RetrievalRequest): RetrievalResult =
            RetrievalResult(
                mode = request.mode,
                injectionLabel = "",
                memories = emptyList(),
            )

        override suspend fun stableVectors(sessionId: String, limit: Int): List<BioVector> = emptyList()
    }

    private fun zeroDelta(): Map<String, Float> = mapOf(
        "L" to 0.0f,
        "P" to 0.0f,
        "E" to 0.0f,
        "S" to 0.0f,
        "tau" to 0.0f,
        "V" to 0.0f,
        "M" to 0.0f,
        "F" to 0.0f,
    )

    private fun testPersonaConfig(): PersonaConfig = PersonaConfig(
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
