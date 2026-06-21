package io.openeden.runtime

import io.openeden.bio.BioVector
import io.openeden.bio.VectorDelta
import io.openeden.memory.RetrievalMode
import io.openeden.persona.EvolutionThresholds
import io.openeden.persona.PersonaConfig
import io.openeden.persona.PersonaMode
import io.openeden.llm.LlmOutput
import io.openeden.prompt.BuiltPrompt
import io.openeden.prompt.PromptSectionKeys
import io.openeden.trace.TraceTag
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class MessagePipelineTest {
    @Test
    fun `runs one development message turn`() = runTest {
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = testPersonaConfig(),
        )

        val result = pipeline.handle(
            DevelopmentMessageRequest(
                platform = "QQ",
                scopeId = "100",
                userId = "u1",
                text = "hello",
                emotionConfidence = 0.49f,
                emotionDelta = VectorDelta(p = -1.0f),
            ),
        )

        assertEquals("QQ:100", result.sessionId)
        assertEquals(RetrievalMode.CONGRUENT, result.retrievalMode)
        assertContains(result.traceTags, TraceTag.CodebookHeuristicFallback)
        assertEquals(1, result.evolutionIndex)
        assertEquals(BioVector.Neutral, result.updatedVector)
        assertContains(result.promptPreview, "\"bio_core_state\"")
        assertEquals("not_triggered", result.diaryOutcome)
    }

    @Test
    fun `enqueues diary event when llm delta changes vector`() = runTest {
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = testPersonaConfig(),
            llmClient = object : io.openeden.llm.LlmClient {
                override suspend fun complete(prompt: BuiltPrompt): LlmOutput = LlmOutput(
                    internalLogic = "logic",
                    vectorDelta = mapOf(
                        "L" to 0.0f,
                        "P" to 0.2f,
                        "E" to 0.0f,
                        "S" to 0.0f,
                        "tau" to 0.0f,
                        "V" to 0.0f,
                        "M" to 0.0f,
                        "F" to 0.0f,
                    ),
                    response = "response",
                )
            },
        )

        val result = pipeline.handle(
            DevelopmentMessageRequest(
                platform = "QQ",
                scopeId = "100",
                userId = "u1",
                text = "hello",
                emotionConfidence = 0.49f,
            ),
        )

        assertEquals("enqueued", result.diaryOutcome)
        assertEquals(0.7f, result.updatedVector.p)
    }

    @Test
    fun `shock back-detection persists shock state and omega jump behind confidence gate`() = runTest {
        val store = MutableSessionStateStore()
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = testPersonaConfig(),
            store = store,
            llmClient = object : io.openeden.llm.LlmClient {
                override suspend fun complete(prompt: BuiltPrompt): LlmOutput = LlmOutput(
                    internalLogic = "a severe discontinuity was inferred from the response",
                    vectorDelta = mapOf(
                        "L" to 0.0f,
                        "P" to -0.5f,
                        "E" to 0.0f,
                        "S" to 0.0f,
                        "tau" to 0.0f,
                        "V" to 0.0f,
                        "M" to 0.0f,
                        "F" to 0.4f,
                    ),
                    response = "response",
                )
            },
        )

        pipeline.handle(
            DevelopmentMessageRequest(
                platform = "QQ",
                scopeId = "100",
                userId = "u1",
                text = "hello",
                emotionConfidence = 0.65f,
            ),
        )

        val state = store.read("QQ:100")
        val shock = assertNotNull(state.shockState)
        assertEquals(true, shock.active)
        assertEquals(0.4f, shock.intensity)
        assertEquals(0.06f, state.omega.value, 0.0001f)
    }

    @Test
    fun `pipeline runs runtime math through inference executor`() = runTest {
        val executor = RecordingInferenceExecutor()
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = testPersonaConfig(),
            inferenceExecutor = executor,
        )

        pipeline.handle(
            DevelopmentMessageRequest(
                platform = "QQ",
                scopeId = "100",
                userId = "u1",
                text = "hello",
                emotionConfidence = 0.49f,
            ),
        )

        assertTrue(executor.calls >= 4)
    }

    private fun testPersonaConfig(): PersonaConfig = PersonaConfig(
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
