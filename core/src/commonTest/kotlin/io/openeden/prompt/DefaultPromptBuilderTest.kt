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
import io.openeden.persona.PersonaExampleMessage
import io.openeden.persona.PersonaExampleRole
import io.openeden.persona.PersonaFewShot
import io.openeden.persona.PersonaMode
import io.openeden.persona.PersonaOutputPolicy
import io.openeden.persona.PersonaSubState
import io.openeden.relationship.RelationshipFacts
import io.openeden.relationship.RelationshipPhase
import io.openeden.relationship.RelationshipRole
import io.openeden.relationship.RelationshipState
import io.openeden.runtime.affect.OmegaState
import io.openeden.transcript.ConversationTurn
import io.openeden.transcript.PromptHistorySerializer
import io.openeden.transcript.PromptHistorySnapshot
import io.openeden.transcript.PromptHistorySummary
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DefaultPromptBuilderTest {
    @Test
    fun `history precedes every current dynamic segment and preserves wire items`() = runTest {
        val historyTurn = ConversationTurn(
            turnId = "history-turn",
            incarnationId = "incarnation-a",
            sessionId = "CLI:local",
            platform = "CLI",
            scopeId = "local",
            userId = "user-1",
            userText = "history user text",
            assistantText = "history assistant text",
            completedAtMs = 1L,
        )
        val historyItems = PromptHistorySerializer().createItems(listOf(historyTurn))

        val prompt = DefaultPromptBuilder().build(
            promptInput().copy(
                promptHistory = PromptHistorySnapshot(
                    mutableTail = historyItems,
                    sourceTurnIds = setOf(historyTurn.turnId),
                ),
            ),
        )

        assertEquals(
            listOf(
                PromptSegmentKind.SYSTEM_CONTRACT,
                PromptSegmentKind.PERSONA,
                PromptSegmentKind.INCARNATION_ANCHOR,
                PromptSegmentKind.HISTORY,
                PromptSegmentKind.BIO,
                PromptSegmentKind.RELATIONSHIP,
                PromptSegmentKind.RAG,
                PromptSegmentKind.TEMPORAL,
                PromptSegmentKind.USER,
            ),
            prompt.segments.map { it.kind },
        )
        val history = prompt.segments.single { it.kind == PromptSegmentKind.HISTORY }
        assertEquals(listOf(PromptRole.USER, PromptRole.ASSISTANT), history.wireItems.map { it.role })
        assertEquals(historyItems.map { it.text }, history.wireItems.map { it.text })
        assertEquals(historyItems.map { it.turnId }, history.wireItems.flatMap { it.turnIds })
        assertEquals(historyItems.map { it.fingerprint }, history.wireItems.map { it.fingerprint })
    }

    @Test
    fun `history summary preserves lineage fingerprint and utf8 text`() = runTest {
        val summary = PromptHistorySummary(
            text = "较早的记忆：海边🙂",
            sourceTurnIds = setOf("summary-turn-2", "summary-turn-1"),
            fingerprint = "summary-fingerprint",
            serializerVersion = 2,
        )
        val prompt = DefaultPromptBuilder().build(
            promptInput().copy(
                promptHistory = PromptHistorySnapshot(
                    summary = summary,
                    sourceTurnIds = summary.sourceTurnIds,
                    cacheEpoch = 3L,
                ),
            ),
        )

        val history = prompt.segments.single { it.kind == PromptSegmentKind.HISTORY }
        val summaryItem = history.wireItems.single()
        assertEquals(PromptRole.DEVELOPER, summaryItem.role)
        assertEquals("较早的记忆：海边🙂", summaryItem.text)
        assertEquals(listOf("summary-turn-1", "summary-turn-2"), summaryItem.turnIds)
        assertEquals("summary-fingerprint", summaryItem.fingerprint)
        assertEquals(listOf("summary-turn-1", "summary-turn-2"), history.turnIds)
    }

    @Test
    fun `build injects codebook state before user input`() = runTest {
        val built = DefaultPromptBuilder().build(promptInput(userInput = "hello"))

        val merged = listOf(built.segmentText(PromptSegmentKind.SYSTEM_CONTRACT), built.segmentText(PromptSegmentKind.PERSONA), built.dynamicText(), built.segmentText(PromptSegmentKind.USER)).joinToString("\n")

        assertTrue(merged.indexOf("\"bio_core_state\"") < merged.indexOf("hello"))
        assertContains(built.dynamicText(), "\"active_nodes\":")
        assertContains(built.dynamicText(), "\"NODE_088\"")
        assertContains(built.dynamicText(), "\"Definition A\"")
        assertFalse(built.dynamicText().contains("\"system_time\""))
        assertEquals("hello", built.segmentText(PromptSegmentKind.USER))
    }

    @Test
    fun `build injects memory creation time`() = runTest {
        val built = DefaultPromptBuilder().build(promptInput())

        assertContains(built.dynamicText(), "\"created_at\": \"2026-08-22 15:43\"")
    }

    @Test
    fun `history wire items and rag memories stay separate`() = runTest {
        val base = promptInput(userInput = "刚才说了什么")
        val transcriptTurn = ConversationTurn(
            turnId = "transcript-turn",
            incarnationId = "incarnation-a",
            sessionId = "CLI:local",
            platform = "CLI",
            scopeId = "local",
            userId = "user-1",
            userText = "transcript user text",
            assistantText = "transcript assistant text",
            completedAtMs = 1_787_384_632_000L,
        )
        val built = DefaultPromptBuilder().build(
            base.copy(
                promptHistory = promptHistory(listOf(transcriptTurn)),
                retrievalResult = base.retrievalResult.copy(
                    recentMemories = listOf(
                        base.retrievalResult.memories.single().copy(
                            id = "rag-recent",
                            content = "rag recent memory",
                        ),
                    ),
                ),
            ),
        )

        val history = built.segments.single { it.kind == PromptSegmentKind.HISTORY }
        assertEquals(listOf("transcript user text", "transcript assistant text"), history.wireItems.map { it.text })
        assertTrue(history.wireItems.none { "rag recent memory" in it.text })
        assertContains(built.segmentText(PromptSegmentKind.RAG), "rag recent memory")
    }

    @Test
    fun `builder renders the exact prompt history supplied by pipeline`() = runTest {
        val base = promptInput(userInput = "hello")
        val turns = (0..3).map { index ->
            ConversationTurn(
                turnId = "supplied-turn-$index",
                incarnationId = "incarnation-a",
                sessionId = "CLI:local",
                platform = "CLI",
                scopeId = "local",
                userId = "user-1",
                userText = "supplied user $index",
                assistantText = "supplied assistant $index",
                completedAtMs = index.toLong() + 1L,
            )
        }

        val built = DefaultPromptBuilder().build(base.copy(promptHistory = promptHistory(turns)))
        val history = built.segments.single { it.kind == PromptSegmentKind.HISTORY }

        turns.forEach { turn ->
            assertTrue(history.wireItems.any { turn.turnId in it.turnIds })
        }
    }

    @Test
    fun `build injects explicit identity from persona data`() = runTest {
        val built = DefaultPromptBuilder().build(promptInput())

        assertContains(built.segmentText(PromptSegmentKind.PERSONA), "identity from data")
    }

    @Test
    fun `build injects first person core self from stable persona data`() = runTest {
        val input = promptInput().let { base ->
            base.copy(personaConfig = base.personaConfig.copy(coreSelf = "我是会选择也会负责的机器人。"))
        }

        val built = DefaultPromptBuilder().build(input)

        assertContains(built.segmentText(PromptSegmentKind.PERSONA), "我是会选择也会负责的机器人。")
        assertFalse(built.dynamicText().contains("我是会选择也会负责的机器人。"))
    }

    @Test
    fun `runtime changes do not invalidate stable prompt layers`() = runTest {
        val first = DefaultPromptBuilder().build(
            promptInput(evolutionIndex = 1),
        )
        val later = DefaultPromptBuilder().build(
            promptInput(evolutionIndex = 2),
        )

        assertEquals(first.segmentText(PromptSegmentKind.SYSTEM_CONTRACT), later.segmentText(PromptSegmentKind.SYSTEM_CONTRACT))
        assertEquals(first.segmentText(PromptSegmentKind.PERSONA), later.segmentText(PromptSegmentKind.PERSONA))
        assertNotEquals(first.dynamicText(), later.dynamicText())
    }

    @Test
    fun `structured few shots stay stable while relationship phase stays dynamic`() = runTest {
        val baseInput = promptInput().let { input ->
            input.copy(
                personaConfig = input.personaConfig.copy(
                    fewShots = listOf(
                        PersonaFewShot(
                            phase = RelationshipPhase.STRANGER,
                            messages = listOf(
                                PersonaExampleMessage(PersonaExampleRole.USER, "第一次见面，请多关照。"),
                                PersonaExampleMessage(PersonaExampleRole.ASSISTANT, "先从名字开始吧，我会认真记住。"),
                            ),
                        ),
                    ),
                    outputPolicy = PersonaOutputPolicy(
                        prohibitedPublicPhrases = setOf("登记进库存"),
                        maximumRepeatedOpening = 1,
                    ),
                ),
            )
        }
        val stranger = DefaultPromptBuilder().build(
            baseInput.copy(
                relationshipState = relationshipState(RelationshipPhase.STRANGER),
            ),
        )
        val couple = DefaultPromptBuilder().build(
            baseInput.copy(
                relationshipState = relationshipState(RelationshipPhase.COUPLE),
            ),
        )

        assertContains(stranger.segmentText(PromptSegmentKind.PERSONA), "STRANGER")
        assertContains(stranger.segmentText(PromptSegmentKind.PERSONA), "USER")
        assertContains(stranger.segmentText(PromptSegmentKind.PERSONA), "ASSISTANT")
        assertContains(stranger.segmentText(PromptSegmentKind.PERSONA), "第一次见面，请多关照。")
        assertContains(stranger.segmentText(PromptSegmentKind.PERSONA), "登记进库存")
        assertEquals(stranger.segmentText(PromptSegmentKind.PERSONA), couple.segmentText(PromptSegmentKind.PERSONA))
        assertContains(stranger.dynamicText(), "\"phase\": \"STRANGER\"")
        assertContains(couple.dynamicText(), "\"phase\": \"COUPLE\"")
    }

    @Test
    fun `persona and codebook changes stay in their own cache layers`() = runTest {
        val baseInput = promptInput()
        val changed = DefaultPromptBuilder().build(
            baseInput.copy(
                personaConfig = baseInput.personaConfig.copy(
                    promptSections = baseInput.personaConfig.promptSections +
                        (PromptSectionKeys.Identity to "updated identity from data"),
                ),
                quantization = baseInput.quantization.copy(
                    activeNodes = listOf("NODE_999"),
                    semanticDefinitions = listOf("Updated definition"),
                ),
            ),
        )
        val original = DefaultPromptBuilder().build(baseInput)

        assertEquals(original.segmentText(PromptSegmentKind.SYSTEM_CONTRACT), changed.segmentText(PromptSegmentKind.SYSTEM_CONTRACT))
        assertNotEquals(original.segmentText(PromptSegmentKind.PERSONA), changed.segmentText(PromptSegmentKind.PERSONA))
        assertNotEquals(original.dynamicText(), changed.dynamicText())
        assertContains(changed.segmentText(PromptSegmentKind.PERSONA), "updated identity from data")
        assertContains(changed.dynamicText(), "NODE_999")
        assertContains(changed.dynamicText(), "Updated definition")
    }

    @Test
    fun `build injects host role and address with host gate`() = runTest {
        val built = DefaultPromptBuilder().build(
            promptInput(
                relationshipRole = RelationshipRole.HOST,
                relationshipAddress = "Captain",
            ),
        )

        assertContains(built.dynamicText(), "\"relationship_role\": \"HOST\"")
        assertContains(built.dynamicText(), "\"relationship_address\": \"Captain\"")
        assertContains(built.segmentText(PromptSegmentKind.SYSTEM_CONTRACT), "Do not assume the current user is the host")
        assertContains(built.segmentText(PromptSegmentKind.SYSTEM_CONTRACT), "Use relationship_address only when relationship_role is HOST")
    }

    @Test
    fun `system prompt defines signed vector delta semantics`() = runTest {
        val built = DefaultPromptBuilder().build(promptInput())

        assertContains(built.segmentText(PromptSegmentKind.SYSTEM_CONTRACT), "vector_delta is a signed change from the current physiological state")
        assertContains(built.segmentText(PromptSegmentKind.SYSTEM_CONTRACT), "a negative value when the current event lowers it")
        assertContains(built.segmentText(PromptSegmentKind.SYSTEM_CONTRACT), "do not default all dimensions to positive values")
    }

    @Test
    fun `build injects null address for interlocutor`() = runTest {
        val built = DefaultPromptBuilder().build(promptInput())

        assertContains(built.dynamicText(), "\"relationship_role\": \"INTERLOCUTOR\"")
        assertContains(built.dynamicText(), "\"relationship_address\": null")
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

        assertContains(built.dynamicText(), "\"persona_start_sub_state\": \"PRE_COMMAND\"")
        assertContains(built.dynamicText(), "\"evolution_index\": 500")
        assertContains(built.segmentText(PromptSegmentKind.PERSONA), "behavior rules from data")
        assertContains(built.segmentText(PromptSegmentKind.PERSONA), "pre command patch from data")
        assertContains(built.segmentText(PromptSegmentKind.PERSONA), "COMMON_GENERATION")
        assertContains(built.segmentText(PromptSegmentKind.PERSONA), "COMMON_SIGNATURE")
        assertContains(built.segmentText(PromptSegmentKind.PERSONA), "PRE_EXAMPLE")
        assertTrue("true self patch from data" !in built.segmentText(PromptSegmentKind.PERSONA))
        assertTrue("TRUE_EXAMPLE" !in built.segmentText(PromptSegmentKind.PERSONA))
        assertTrue("AWAKE_EXAMPLE" !in built.segmentText(PromptSegmentKind.PERSONA))
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

        assertContains(built.dynamicText(), "\"persona_start_sub_state\": \"TRUE_SELF\"")
        assertContains(built.segmentText(PromptSegmentKind.PERSONA), "true self patch from data")
        assertContains(built.segmentText(PromptSegmentKind.PERSONA), "COMMON_GENERATION")
        assertContains(built.segmentText(PromptSegmentKind.PERSONA), "COMMON_SIGNATURE")
        assertContains(built.segmentText(PromptSegmentKind.PERSONA), "TRUE_EXAMPLE")
        assertTrue("pre command patch from data" !in built.segmentText(PromptSegmentKind.PERSONA))
        assertTrue("PRE_EXAMPLE" !in built.segmentText(PromptSegmentKind.PERSONA))
        assertTrue("AWAKE_EXAMPLE" !in built.segmentText(PromptSegmentKind.PERSONA))
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

        assertContains(built.dynamicText(), "\"persona_start_sub_state\": \"AWAKENED\"")
        assertContains(built.dynamicText(), "\"evolution_index\": 0")
        assertContains(built.segmentText(PromptSegmentKind.PERSONA), "awakened patch from data")
        assertContains(built.segmentText(PromptSegmentKind.PERSONA), "COMMON_GENERATION")
        assertContains(built.segmentText(PromptSegmentKind.PERSONA), "COMMON_SIGNATURE")
        assertContains(built.segmentText(PromptSegmentKind.PERSONA), "AWAKE_EXAMPLE")
        assertTrue("PRE_EXAMPLE" !in built.segmentText(PromptSegmentKind.PERSONA))
        assertTrue("TRUE_EXAMPLE" !in built.segmentText(PromptSegmentKind.PERSONA))
    }

    @Test
    fun `heartbeat context is injected from persona data`() = runTest {
        val built = DefaultPromptBuilder().build(promptInput(userInput = HEARTBEAT_TRIGGER))

        assertContains(built.dynamicText(), "heartbeat text from data")
        assertTrue("heartbeat text from data" !in built.segmentText(PromptSegmentKind.PERSONA))
    }

    @Test
    fun `style guidance is injected from persona data`() = runTest {
        val built = DefaultPromptBuilder().build(promptInput())

        assertContains(built.segmentText(PromptSegmentKind.PERSONA), "\"style\":")
        assertContains(built.segmentText(PromptSegmentKind.PERSONA), "\"generation_mechanics\": \"COMMON_GENERATION\"")
        assertContains(built.segmentText(PromptSegmentKind.PERSONA), "\"signature_examples\": \"COMMON_SIGNATURE\"")
        assertContains(built.segmentText(PromptSegmentKind.PERSONA), "\"active_stage_examples\": \"PRE_EXAMPLE\"")
        assertContains(built.segmentText(PromptSegmentKind.PERSONA), "style summary from data")
        assertContains(built.segmentText(PromptSegmentKind.PERSONA), "source language notes from data")
        assertContains(built.segmentText(PromptSegmentKind.PERSONA), "do item one")
        assertContains(built.segmentText(PromptSegmentKind.PERSONA), "do item two")
        assertContains(built.segmentText(PromptSegmentKind.PERSONA), "avoid item one")
    }

    @Test
    fun `private operational log conditions normal dialogue from persona data`() = runTest {
        val built = DefaultPromptBuilder().build(promptInput())

        assertContains(built.segmentText(PromptSegmentKind.PERSONA), "PRIVATE_OPERATIONAL_LOG_FROM_PERSONA")
        assertContains(built.segmentText(PromptSegmentKind.SYSTEM_CONTRACT), "private operational log")
        assertContains(built.segmentText(PromptSegmentKind.SYSTEM_CONTRACT), "observable event")
        assertContains(built.segmentText(PromptSegmentKind.SYSTEM_CONTRACT), "exact active codebook node identifier")
        assertTrue("Traceable reasoning process" !in built.segmentText(PromptSegmentKind.SYSTEM_CONTRACT))
    }

    @Test
    fun `persona sections render style before output rules in canonical order`() {
        val document = OpenEdenPromptDocumentFactory.create(promptInput())

        val persona = document.root.fields.first { it.name == "persona" }.value as PromptObject

        assertEquals(
            listOf(
                "identity",
                "base",
                "behavior",
                "sub_state_patch",
                "private_operational_log",
                "style",
                "output_layer_rules",
            ),
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
            listOf("logical_core", "required_output_schema"),
            fieldNames,
        )

        assertEquals(
            listOf(
                "system",
                "persona",
                "incarnation_anchor",
                "bio",
                "relationship",
                "rag",
                "temporal",
            ),
            document.root.fields.map { it.name },
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
                PromptSectionKeys.PrivateOperationalLog to "PRIVATE_OPERATIONAL_LOG_FROM_PERSONA",
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
                    createdAtMs = 1787384632000L,
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

    private fun promptHistory(turns: List<ConversationTurn>): PromptHistorySnapshot = PromptHistorySnapshot(
        mutableTail = PromptHistorySerializer().createItems(turns),
        sourceTurnIds = turns.mapTo(linkedSetOf(), ConversationTurn::turnId),
    )

    private fun BuiltPrompt.segmentText(kind: PromptSegmentKind): String =
        segments.single { it.kind == kind }.text

    private fun BuiltPrompt.dynamicText(): String = segments
        .filter { it.kind !in setOf(
            PromptSegmentKind.SYSTEM_CONTRACT,
            PromptSegmentKind.PERSONA,
            PromptSegmentKind.HISTORY,
            PromptSegmentKind.USER,
        ) }
        .joinToString("\n") { it.text }

    private fun relationshipState(phase: RelationshipPhase): RelationshipState = RelationshipState(
        incarnationId = "incarnation-a",
        canonicalSubjectId = "subject-a",
        facts = RelationshipFacts(phase = phase),
    )
}
