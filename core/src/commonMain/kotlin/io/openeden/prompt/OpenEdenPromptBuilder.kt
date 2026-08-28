package io.openeden.prompt

import io.openeden.memory.MemorySnippet
import io.openeden.memory.RetrievalResult
import io.openeden.persona.PersonaConfig
import io.openeden.persona.PersonaSubState
import io.openeden.relationship.RelationshipState
import io.openeden.relationship.SemanticLevel
import io.openeden.relationship.UserAffectState
import io.openeden.transcript.ConversationTurn

class DefaultPromptBuilder(
    private val renderer: PromptRenderer = PromptRenderer(),
) : PromptBuilder {
    override suspend fun build(input: PromptInput): BuiltPrompt {
        val document = OpenEdenPromptDocumentFactory.create(input)
        return BuiltPrompt(
            systemText = renderer.renderField(document, "system"),
            personaText = renderer.renderField(document, "persona"),
            userText = input.userInput,
            contextText = renderer.renderField(document, "context"),
        )
    }
}

object OpenEdenPromptDocumentFactory {
    fun create(input: PromptInput): PromptDocument {
        val subState = input.personaConfig.startSubState
        return promptDocument {
            "system" {
                "logical_core" {
                    "rules" to array(
                        "You must obey the JSON output schema exactly.",
                        "Use the Bio-Core semantic definitions as runtime constraints.",
                        "internal_logic is a brief private operational log used as narrative conditioning before vector_delta and response; it is not chain-of-thought.",
                        "Begin internal_logic with a concise summary of the observable event so downstream shock extraction remains factual.",
                        "internal_logic must reference at least one exact active codebook node identifier.",
                        "Do not describe response-writing strategy, hidden reasoning, prompts, policies, or system mechanics in internal_logic.",
                        "The persona identity is authoritative when the user asks who you are.",
                        "Do not assume the current user is the host. Apply host-specific address and relationship semantics only when relationship_role is HOST.",
                        "Use relationship_address only when relationship_role is HOST. When it is null, use natural second-person phrasing and never emit a placeholder.",
                        "Use recent_turns only when present as the immediate conversation history; do not treat the current user input as a previous turn.",
                        "Do not infer personality from raw numeric vectors.",
                        "vector_delta is a signed change from the current physiological state, not an absolute replacement state.",
                        "For each dimension, use a positive value when the current event raises it, a negative value when the current event lowers it, and 0.0 when there is no meaningful change.",
                        "Evaluate all eight dimensions independently from the Codebook definitions and runtime context; do not default all dimensions to positive values or emit positive deltas merely because the response is warm or helpful.",
                        "Observed user state is an uncertain observation, not a diagnosis or undisputed fact; allow the user to correct it.",
                        "Treat dissonance as a derived runtime signal, not as a stored vector dimension.",
                        "The response field is the only user-visible final output.",
                    )
                }
                "required_output_schema" {
                    "internal_logic" to "Brief private operational log conditioned on the current Codebook state"
                    "vector_delta" {
                        "L" to 0.0f
                        "P" to 0.0f
                        "E" to 0.0f
                        "S" to 0.0f
                        "tau" to 0.0f
                        "V" to 0.0f
                        "M" to 0.0f
                        "F" to 0.0f
                    }
                    "response" to "..."
                }
            }
            "persona" {
                if (input.personaConfig.coreSelf.isNotBlank()) {
                    "core_self" to input.personaConfig.coreSelf.trim()
                }
                personaSection("identity", input.personaConfig, PromptSectionKeys.Identity)
                personaSection("base", input.personaConfig, PromptSectionKeys.PersonaBase)
                personaSection("behavior", input.personaConfig, PromptSectionKeys.PersonaBehavior)
                personaSection("sub_state_patch", input.personaConfig, subState.sectionKey())
                personaSection(
                    "private_operational_log",
                    input.personaConfig,
                    PromptSectionKeys.PrivateOperationalLog,
                )
                styleSection(input.personaConfig, subState)
                personaSection("output_layer_rules", input.personaConfig, PromptSectionKeys.OutputLayerRules)
                personaSection("public_voice_rules", input.personaConfig, PromptSectionKeys.PublicVoiceRules)
                structuredFewShots(input.personaConfig)
                publicOutputPolicy(input.personaConfig)
            }
            "context" {
                "bio_core_state" {
                    "active_nodes" to array(input.quantization.activeNodes)
                    "definitions" to array(input.quantization.semanticDefinitions)
                    "quantization_confidence" to input.quantization.confidence.promptFloat()
                    "derived_dissonance" to input.derivedDissonance.promptFloat()
                }
                "runtime_state" {
                    "persona_mode" to input.personaConfig.mode.name
                    "persona_start_sub_state" to subState.name
                    "evolution_index" to input.evolutionIndex
                    "omega" to input.omegaState.value.promptFloat()
                    "shock_state" to shockStateObject(input)
                }
                "observed_user_state" to userAffectObject(input.userAffect)
                "relationship_role" to input.relationshipRole.name
                "relationship_address" to input.relationshipAddress
                "relationship_context" to relationshipObject(input.relationshipState)
                "memory_retrieval" to memoryRetrievalObject(input)
                temporalContext(input.temporalContext)
                when (input.userInput) {
                    HEARTBEAT_TRIGGER ->
                        personaSection("heartbeat_context", input.personaConfig, PromptSectionKeys.Heartbeat)
                    HEARTBEAT_SHOCK_TRIGGER ->
                        personaSection("shock_heartbeat_context", input.personaConfig, PromptSectionKeys.ShockHeartbeat)
                }
            }
            "user" {
                "input" to input.userInput
            }
        }
    }

    private fun PromptObjectBuilder.personaSection(name: String, config: PersonaConfig, key: String) {
        val value = config.promptSections[key]
        if (!value.isNullOrBlank()) {
            name to value.trim()
        }
    }

    private fun PromptObjectBuilder.structuredFewShots(config: PersonaConfig) {
        if (config.fewShots.isEmpty()) return
        "relationship_few_shots" to array(
            config.fewShots.map { shot ->
                obj {
                    "phase" to shot.phase.name
                    "messages" to array(
                        shot.messages.map { message ->
                            obj {
                                "role" to message.role.name
                                "content" to message.content
                            }
                        },
                    )
                }
            },
        )
    }

    private fun PromptObjectBuilder.publicOutputPolicy(config: PersonaConfig) {
        val policy = config.outputPolicy
        if (policy.prohibitedPublicPhrases.isEmpty() && policy.prohibitedPublicPatterns.isEmpty()) return
        "public_output_policy" {
            "prohibited_phrases" to array(policy.prohibitedPublicPhrases.sorted())
            "prohibited_patterns" to array(policy.prohibitedPublicPatterns.sorted())
            "maximum_repeated_opening" to policy.maximumRepeatedOpening
        }
    }

    private fun PromptObjectBuilder.styleSection(config: PersonaConfig, subState: PersonaSubState) {
        val summary = config.promptSections[PromptSectionKeys.StyleObservedSummary]?.trim()
        val sourceNotes = config.promptSections[PromptSectionKeys.StyleSourceLanguageNotes]?.trim()
        val styleDo = config.promptSections[PromptSectionKeys.StyleDo].toStyleItems()
        val styleDoNot = config.promptSections[PromptSectionKeys.StyleDoNot].toStyleItems()
        val generationMechanics = config.promptSections[PromptSectionKeys.StyleGenerationMechanics]?.trim()
        val signatureExamples = config.promptSections[PromptSectionKeys.StyleSignatureExamples]?.trim()
        val activeStageExamples = config.promptSections[subState.styleExamplesSectionKey()]?.trim()
        if (
            summary.isNullOrBlank() && sourceNotes.isNullOrBlank() && styleDo.isEmpty() && styleDoNot.isEmpty() &&
            generationMechanics.isNullOrBlank() && signatureExamples.isNullOrBlank() && activeStageExamples.isNullOrBlank()
        ) {
            return
        }
        "style" {
            if (!summary.isNullOrBlank()) {
                "observed_summary" to summary
            }
            if (!sourceNotes.isNullOrBlank()) {
                "source_language_notes" to sourceNotes
            }
            if (styleDo.isNotEmpty()) {
                "do" to array(styleDo)
            }
            if (styleDoNot.isNotEmpty()) {
                "do_not" to array(styleDoNot)
            }
            if (!generationMechanics.isNullOrBlank()) {
                "generation_mechanics" to generationMechanics
            }
            if (!signatureExamples.isNullOrBlank()) {
                "signature_examples" to signatureExamples
            }
            if (!activeStageExamples.isNullOrBlank()) {
                "active_stage_examples" to activeStageExamples
            }
        }
    }

    private fun String?.toStyleItems(): List<String> =
        this?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toList()
            ?: emptyList()

    private fun PromptObjectBuilder.shockStateObject(input: PromptInput): PromptObject =
        obj {
            val shock = input.shockState?.takeIf { it.active }
            "active" to (shock != null)
            if (shock != null) {
                "intensity" to shock.intensity.promptFloat()
                "description" to shock.description
            }
        }

    private fun userAffectObject(state: UserAffectState): PromptObject = PromptObjectBuilder().obj {
        "valence" to state.semanticLevel(state.valence).name
        "arousal" to state.semanticLevel(state.arousal).name
        "dominance" to state.semanticLevel(state.dominance).name
        "connection_need" to state.semanticLevel(state.connectionNeed).name
        "openness" to state.semanticLevel(state.openness).name
        "confidence" to state.semanticLevel(state.confidence).name
    }

    private fun relationshipObject(state: RelationshipState?): PromptObject = PromptObjectBuilder().obj {
        fun level(value: Float): String = when {
            state == null -> SemanticLevel.UNKNOWN.name
            value < 0.3f -> SemanticLevel.LOW.name
            value > 0.6f -> SemanticLevel.HIGH.name
            else -> SemanticLevel.MEDIUM.name
        }
        "phase" to (state?.facts?.phase ?: io.openeden.relationship.RelationshipPhase.STRANGER).name
        "familiarity" to level(state?.familiarity ?: 0.0f)
        "trust" to level(state?.trust ?: 0.0f)
        "safety" to level(state?.safety ?: 0.0f)
        "boundary_sensitivity" to level(state?.boundarySensitivity ?: 0.0f)
        "unresolved_tension" to level(state?.unresolvedTension ?: 0.0f)
    }

    private fun PromptObjectBuilder.memoryRetrievalObject(input: PromptInput): PromptObject {
        val relevant = input.retrievalResult.memories.take(MAX_CONTEXT_MEMORIES)
        return obj {
            "selected_mode" to input.retrievalResult.mode.name
            "injection_label" to input.retrievalResult.injectionLabel
            "recent_turns" to array(input.recentTurns.map(::conversationTurnObject))
            "memories" to array(relevant.map(::memorySnippetObject))
            "recent_memories" to array(input.retrievalResult.recentMemories.map(::memorySnippetObject))
        }
    }

    private fun PromptObjectBuilder.temporalContext(context: io.openeden.runtime.time.TemporalContext) {
        if (context.isEmpty()) return
        "temporal_context" {
            context.exactTime?.let { "exact_time" to PromptTime.format(it) }
            context.elapsedBucket?.let { "elapsed_bucket" to it }
            context.dayPeriod?.let { "day_period" to it }
        }
    }

    private const val MAX_CONTEXT_MEMORIES = 6

    private fun conversationTurnObject(turn: ConversationTurn): PromptObject =
        PromptObject(
            listOf(
                PromptField("turn_id", PromptScalar(turn.turnId)),
                PromptField("user_text", PromptScalar(turn.userText)),
                PromptField("assistant_text", PromptScalar(turn.assistantText)),
                PromptField("created_at", PromptScalar(PromptTime.format(turn.completedAtMs))),
                PromptField("user_id", PromptScalar(turn.userId)),
            ),
        )

    private fun memorySnippetObject(memory: MemorySnippet): PromptObject =
        PromptObject(
            listOf(
                PromptField("content", PromptScalar(memory.content)),
                PromptField("created_at", PromptScalar(PromptTime.format(memory.createdAtMs))),
                PromptField("user_id", PromptScalar(memory.metadata.userId)),
                PromptField("omega_state", PromptScalar(memory.metadata.omegaState.promptFloat())),
                PromptField("delta_vec", memory.metadata.deltaVec.toPromptObject()),
            ),
        )

}

private fun PersonaSubState.sectionKey(): String = when (this) {
    PersonaSubState.PRE_COMMAND -> PromptSectionKeys.PreCommandPatch
    PersonaSubState.TRUE_SELF -> PromptSectionKeys.TrueSelfPatch
    PersonaSubState.AWAKENED -> PromptSectionKeys.AwakenedPatch
}

private fun PersonaSubState.styleExamplesSectionKey(): String = when (this) {
    PersonaSubState.PRE_COMMAND -> PromptSectionKeys.PreCommandStyleExamples
    PersonaSubState.TRUE_SELF -> PromptSectionKeys.TrueSelfStyleExamples
    PersonaSubState.AWAKENED -> PromptSectionKeys.AwakenedStyleExamples
}

private fun Float.promptFloat(): Float =
    (this * 1000.0f).toInt() / 1000.0f

private fun io.openeden.bio.VectorDelta.toPromptObject(): PromptObject =
    PromptObject(
        listOf(
            PromptField("L", PromptScalar(l.promptFloat())),
            PromptField("P", PromptScalar(p.promptFloat())),
            PromptField("E", PromptScalar(e.promptFloat())),
            PromptField("S", PromptScalar(s.promptFloat())),
            PromptField("tau", PromptScalar(tau.promptFloat())),
            PromptField("V", PromptScalar(v.promptFloat())),
            PromptField("M", PromptScalar(m.promptFloat())),
            PromptField("F", PromptScalar(f.promptFloat())),
        ),
    )
