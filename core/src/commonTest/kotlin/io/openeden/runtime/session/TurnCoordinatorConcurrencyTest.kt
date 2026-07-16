package io.openeden.runtime.session

import io.openeden.runtime.pipeline.DevelopmentMessagePipeline
import io.openeden.runtime.pipeline.DevelopmentMessageRequest


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
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

class TurnCoordinatorConcurrencyTest {
    @Test
    fun `same session turns serialize the whole stateful flow`() = runTest {
        val store = MutableSessionStateStore()
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = testPersonaConfig(),
            store = store,
            llmClient = object : LlmClient {
                override suspend fun complete(prompt: BuiltPrompt): LlmOutput {
                    delay(1)
                    return LlmOutput(
                        internalLogic = "serialized test turn",
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
        assertEquals(100L, store.read("TEST:shared").evolutionIndex)
        assertTrue(store.read("TEST:shared").vector.p >= 0.99f)
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
