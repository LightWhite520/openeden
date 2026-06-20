package io.openeden.prompt

import io.openeden.bio.BioVector
import io.openeden.bio.VectorDelta
import io.openeden.codebook.QuantizationResult
import io.openeden.memory.MemoryMetadata
import io.openeden.memory.MemorySnippet
import io.openeden.memory.RetrievalMode
import io.openeden.memory.RetrievalResult
import io.openeden.persona.EvolutionThresholds
import io.openeden.persona.PersonaConfig
import io.openeden.persona.PersonaMode
import io.openeden.runtime.OmegaState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultPromptBuilderTest {
    @Test
    fun `build injects codebook state before user input`() = runTest {
        val built = DefaultPromptBuilder().build(promptInput(userInput = "hello"))

        val merged = listOf(built.systemText, built.personaText, built.userText).joinToString("\n")

        assertTrue(merged.indexOf("\"bio_core_state\"") < merged.indexOf("\"input\": \"hello\""))
        assertContains(built.systemText, "\"active_nodes\":")
        assertContains(built.systemText, "\"NODE_088\"")
        assertContains(built.systemText, "\"Definition A\"")
        assertContains(built.userText, "hello")
    }

    @Test
    fun `growth mode selects sub state patch from persona data`() = runTest {
        val built = DefaultPromptBuilder().build(promptInput(evolutionIndex = 15))

        assertContains(built.systemText, "\"persona_sub_state\": \"TRUE_SELF\"")
        assertContains(built.personaText, "behavior rules from data")
        assertContains(built.personaText, "true self patch from data")
    }

    @Test
    fun `legacy mode always uses awakened patch`() = runTest {
        val built = DefaultPromptBuilder().build(
            promptInput(
                evolutionIndex = 0,
                personaMode = PersonaMode.LEGACY,
            ),
        )

        assertContains(built.systemText, "\"persona_sub_state\": \"AWAKENED\"")
        assertContains(built.personaText, "awakened patch from data")
    }

    @Test
    fun `heartbeat context is injected from persona data`() = runTest {
        val built = DefaultPromptBuilder().build(promptInput(userInput = HEARTBEAT_TRIGGER))

        assertContains(built.personaText, "heartbeat text from data")
    }

    @Test
    fun `style guidance is injected from persona data`() = runTest {
        val built = DefaultPromptBuilder().build(promptInput())

        assertContains(built.personaText, "\"style\":")
        assertContains(built.personaText, "style summary from data")
        assertContains(built.personaText, "source language notes from data")
        assertContains(built.personaText, "do item one")
        assertContains(built.personaText, "do item two")
        assertContains(built.personaText, "avoid item one")
    }

    @Test
    fun `generic dsl composes json like prompt documents`() {
        val document = promptDocument {
            "system" {
                "rules" to array("strict", "json")
                "schema" {
                    "response" to "..."
                }
            }
            "user" {
                "input" to "hello"
            }
        }
        val rendered = PromptRenderer().render(document)

        assertContains(rendered, "\"system\":")
        assertContains(rendered, "\"rules\":")
        assertContains(rendered, "\"strict\"")
        assertContains(rendered, "\"input\": \"hello\"")
    }

    @Test
    fun `factory exposes reusable prompt document before rendering`() {
        val document = OpenEdenPromptDocumentFactory.create(promptInput(evolutionIndex = 15))

        val system = document.root.fields.first { it.name == "system" }.value as PromptObject
        val fieldNames = system.fields.map { it.name }

        assertEquals(
            listOf(
                "logical_core",
                "bio_core_state",
                "runtime_state",
                "memory_retrieval",
                "required_output_schema",
            ),
            fieldNames,
        )
    }

    private fun promptInput(
        evolutionIndex: Long = 0,
        personaMode: PersonaMode = PersonaMode.GROWTH,
        userInput: String = "hello",
    ): PromptInput = PromptInput(
        personaConfig = PersonaConfig(
            mode = personaMode,
            evolutionThresholds = EvolutionThresholds(threshold1 = 10, threshold2 = 20),
            promptSections = mapOf(
                PromptSectionKeys.PersonaBase to "base persona from data",
                PromptSectionKeys.PersonaBehavior to "behavior rules from data",
                PromptSectionKeys.OutputLayerRules to "output rules from data",
                PromptSectionKeys.PreCommandPatch to "pre command patch from data",
                PromptSectionKeys.TrueSelfPatch to "true self patch from data",
                PromptSectionKeys.AwakenedPatch to "awakened patch from data",
                PromptSectionKeys.Heartbeat to "heartbeat text from data",
                PromptSectionKeys.ShockHeartbeat to "shock heartbeat text from data",
                PromptSectionKeys.StyleObservedSummary to "style summary from data",
                PromptSectionKeys.StyleSourceLanguageNotes to "source language notes from data",
                PromptSectionKeys.StyleDo to "do item one\ndo item two",
                PromptSectionKeys.StyleDoNot to "avoid item one",
            ),
        ),
        evolutionIndex = evolutionIndex,
        vectorSnapshot = BioVector.Neutral,
        derivedDissonance = 0.25f,
        quantization = QuantizationResult(
            activeNodes = listOf("NODE_088"),
            semanticDefinitions = listOf("Definition A", "Definition B"),
            confidence = 0.9f,
        ),
        retrievalResult = RetrievalResult(
            mode = RetrievalMode.CONGRUENT,
            injectionLabel = "[memory]",
            memories = listOf(
                MemorySnippet(
                    content = "remembered content",
                    metadata = MemoryMetadata(
                        snapshot8D = BioVector.Neutral,
                        omegaState = 0.2f,
                        deltaVec = VectorDelta(p = 0.1f),
                        snapshotOrigin = BioVector.Neutral,
                        userId = "user-1",
                    ),
                ),
            ),
        ),
        omegaState = OmegaState(0.1f),
        shockState = null,
        userInput = userInput,
    )
}
