package io.openeden.runtime

import io.openeden.bio.BioVector
import io.openeden.llm.LlmOutput
import io.openeden.llm.LlmClient
import io.openeden.persona.EvolutionThresholds
import io.openeden.persona.PersonaConfig
import io.openeden.persona.PersonaMode
import io.openeden.prompt.BuiltPrompt
import io.openeden.prompt.PromptSectionKeys
import io.openeden.trace.TraceTag
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class RuntimePipelineTest {
    @Test
    fun `local runtime request maps to CLI session and persists state`() = runTest {
        val store = MutableSessionStateStore()
        val pipeline = OpenEdenRuntimePipeline.local(
            personaConfig = testPersonaConfig(),
            store = store,
            llmClient = object : LlmClient {
                override suspend fun complete(prompt: BuiltPrompt): LlmOutput = LlmOutput(
                    internalLogic = "local runtime contract test",
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
                userId = "owner",
                text = "hello",
                emotionConfidence = 0.49f,
            ),
        )

        assertEquals("CLI:owner", result.sessionId)
        assertEquals("ok", result.response)
        assertEquals(1, result.evolutionIndex)
        assertEquals(BioVector.Neutral.copy(p = 0.6f), store.read("CLI:owner").vector)
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
                        internalLogic = "history test",
                        vectorDelta = zeroDelta(),
                        response = "first response",
                    )
                }
            },
        )

        pipeline.handle(LocalRuntimeRequest(userId = "owner", text = "first question", emotionConfidence = 0.0f))
        pipeline.handle(LocalRuntimeRequest(userId = "owner", text = "我刚刚说了什么", emotionConfidence = 0.0f))

        assertEquals(2, prompts.size)
        assertTrue(prompts[1].systemText.contains("first question"))
        assertTrue(prompts[1].systemText.contains("first response"))
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
