package io.openeden.prompt

import io.openeden.runtime.affect.ShockState


import io.openeden.bio.BioVector
import io.openeden.bio.VectorDelta
import io.openeden.codebook.QuantizationResult
import io.openeden.memory.MemoryMetadata
import io.openeden.memory.MemorySnippet
import io.openeden.memory.RetrievalMode
import io.openeden.memory.RetrievalResult
import io.openeden.persona.MapPersonaLoader
import io.openeden.persona.PersonaConfig
import io.openeden.persona.PersonaMode
import io.openeden.persona.PersonaSubState
import io.openeden.relationship.RelationshipRole
import io.openeden.runtime.affect.OmegaState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun `build injects explicit identity from persona data`() = runTest {
        val built = DefaultPromptBuilder().build(promptInput())

        assertContains(built.personaText, "identity from data")
    }

    @Test
    fun `build injects host role and address with host gate`() = runTest {
        val built = DefaultPromptBuilder().build(
            promptInput(
                relationshipRole = RelationshipRole.HOST,
                relationshipAddress = "Captain",
            ),
        )

        assertContains(built.systemText, "\"relationship_role\": \"HOST\"")
        assertContains(built.systemText, "\"relationship_address\": \"Captain\"")
        assertContains(built.systemText, "Do not assume the current user is the host")
        assertContains(built.systemText, "Use relationship_address only when relationship_role is HOST")
    }

    @Test
    fun `build injects null address for interlocutor`() = runTest {
        val built = DefaultPromptBuilder().build(promptInput())

        assertContains(built.systemText, "\"relationship_role\": \"INTERLOCUTOR\"")
        assertContains(built.systemText, "\"relationship_address\": null")
    }

    @Test
    fun `prompt input rejects relationship address for interlocutor`() {
        assertFailsWith<IllegalArgumentException> {
            promptInput(
                relationshipRole = RelationshipRole.INTERLOCUTOR,
                relationshipAddress = "Captain",
            )
        }
    }

    @Test
    fun `prompt input rejects blank host address`() {
        assertFailsWith<IllegalArgumentException> {
            promptInput(
                relationshipRole = RelationshipRole.HOST,
                relationshipAddress = " ",
            )
        }
    }

    @Test
    fun `pre command starting point keeps its patch and examples at high evolution index`() = runTest {
        val built = DefaultPromptBuilder().build(promptInput(evolutionIndex = 500))

        assertContains(built.systemText, "\"persona_start_sub_state\": \"PRE_COMMAND\"")
        assertContains(built.systemText, "\"evolution_index\": 500")
        assertContains(built.personaText, "behavior rules from data")
        assertContains(built.personaText, "pre command patch from data")
        assertContains(built.personaText, "COMMON_GENERATION")
        assertContains(built.personaText, "COMMON_SIGNATURE")
        assertContains(built.personaText, "PRE_EXAMPLE")
        assertTrue("true self patch from data" !in built.personaText)
        assertTrue("TRUE_EXAMPLE" !in built.personaText)
        assertTrue("AWAKE_EXAMPLE" !in built.personaText)
    }

    @Test
    fun `growth mode supports explicit true self starting point`() = runTest {
        val persona = MapPersonaLoader.load(
            mapOf(
                "mode" to "growth",
                "start_sub_state" to "true_self",
                "persona.base" to "base persona from data",
                "persona.behavior" to "behavior rules from data",
                "output.layer.rules" to "output rules from data",
                "persona.patch.pre_command" to "pre command patch from data",
                "persona.patch.true_self" to "true self patch from data",
                "persona.patch.awakened" to "awakened patch from data",
                "heartbeat.base" to "heartbeat text from data",
                "heartbeat.shock" to "shock heartbeat text from data",
                "diary.narrative" to "diary text from data",
                PromptSectionKeys.StyleGenerationMechanics to "COMMON_GENERATION",
                PromptSectionKeys.StyleSignatureExamples to "COMMON_SIGNATURE",
                PromptSectionKeys.PreCommandStyleExamples to "PRE_EXAMPLE",
                PromptSectionKeys.TrueSelfStyleExamples to "TRUE_EXAMPLE",
                PromptSectionKeys.AwakenedStyleExamples to "AWAKE_EXAMPLE",
            ),
        )
        val built = DefaultPromptBuilder().build(
            promptInput(evolutionIndex = 0, personaConfigOverride = persona),
        )

        assertContains(built.systemText, "\"persona_start_sub_state\": \"TRUE_SELF\"")
        assertContains(built.personaText, "true self patch from data")
        assertContains(built.personaText, "COMMON_GENERATION")
        assertContains(built.personaText, "COMMON_SIGNATURE")
        assertContains(built.personaText, "TRUE_EXAMPLE")
        assertTrue("pre command patch from data" !in built.personaText)
        assertTrue("PRE_EXAMPLE" !in built.personaText)
        assertTrue("AWAKE_EXAMPLE" !in built.personaText)
    }

    @Test
    fun `awakened starting point uses only awakened examples at zero evolution index`() = runTest {
        val built = DefaultPromptBuilder().build(
            promptInput(
                evolutionIndex = 0,
                personaMode = PersonaMode.LEGACY,
                personaStartSubState = PersonaSubState.AWAKENED,
            ),
        )

        assertContains(built.systemText, "\"persona_start_sub_state\": \"AWAKENED\"")
        assertContains(built.systemText, "\"evolution_index\": 0")
        assertContains(built.personaText, "awakened patch from data")
        assertContains(built.personaText, "COMMON_GENERATION")
        assertContains(built.personaText, "COMMON_SIGNATURE")
        assertContains(built.personaText, "AWAKE_EXAMPLE")
        assertTrue("PRE_EXAMPLE" !in built.personaText)
        assertTrue("TRUE_EXAMPLE" !in built.personaText)
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
        assertContains(built.personaText, "\"generation_mechanics\": \"COMMON_GENERATION\"")
        assertContains(built.personaText, "\"signature_examples\": \"COMMON_SIGNATURE\"")
        assertContains(built.personaText, "\"active_stage_examples\": \"PRE_EXAMPLE\"")
        assertContains(built.personaText, "style summary from data")
        assertContains(built.personaText, "source language notes from data")
        assertContains(built.personaText, "do item one")
        assertContains(built.personaText, "do item two")
        assertContains(built.personaText, "avoid item one")
    }

    @Test
    fun `persona sections render style before output rules in canonical order`() {
        val document = OpenEdenPromptDocumentFactory.create(promptInput())

        val persona = document.root.fields.first { it.name == "persona" }.value as PromptObject

        assertEquals(
            listOf("identity", "base", "behavior", "sub_state_patch", "style", "output_layer_rules"),
            persona.fields.map { it.name },
        )
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
                "observed_user_state",
                "relationship_role",
                "relationship_address",
                "relationship_context",
                "memory_retrieval",
                "required_output_schema",
            ),
            fieldNames,
        )
    }

    private fun promptInput(
        evolutionIndex: Long = 0,
        personaMode: PersonaMode = PersonaMode.GROWTH,
        personaStartSubState: PersonaSubState = PersonaSubState.PRE_COMMAND,
        userInput: String = "hello",
        personaConfigOverride: PersonaConfig? = null,
        relationshipRole: RelationshipRole = RelationshipRole.INTERLOCUTOR,
        relationshipAddress: String? = null,
    ): PromptInput = PromptInput(
        personaConfig = personaConfigOverride ?: PersonaConfig(
            mode = personaMode,
            startSubState = personaStartSubState,
            promptSections = mapOf(
                PromptSectionKeys.Identity to "identity from data",
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
                PromptSectionKeys.StyleGenerationMechanics to "COMMON_GENERATION",
                PromptSectionKeys.StyleSignatureExamples to "COMMON_SIGNATURE",
                PromptSectionKeys.PreCommandStyleExamples to "PRE_EXAMPLE",
                PromptSectionKeys.TrueSelfStyleExamples to "TRUE_EXAMPLE",
                PromptSectionKeys.AwakenedStyleExamples to "AWAKE_EXAMPLE",
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
        relationshipRole = relationshipRole,
        relationshipAddress = relationshipAddress,
    )
}
