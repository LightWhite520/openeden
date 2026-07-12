package io.openeden.prompt

import io.openeden.memory.MemorySnippet
import io.openeden.memory.RetrievalResult
import io.openeden.persona.PersonaConfig
import io.openeden.persona.PersonaMode
import io.openeden.persona.PersonaSubState
import io.openeden.persona.PersonaSubStateSelector
import io.openeden.relationship.RelationshipState
import io.openeden.relationship.SemanticLevel
import io.openeden.relationship.UserAffectState

class DefaultPromptBuilder(
    private val renderer: PromptRenderer = PromptRenderer(),
) : PromptBuilder {
    override suspend fun build(input: PromptInput): BuiltPrompt {
        val document = OpenEdenPromptDocumentFactory.create(input)
        return BuiltPrompt(
            systemText = renderer.renderField(document, "system"),
            personaText = renderer.renderField(document, "persona"),
            userText = renderer.renderField(document, "user"),
        )
    }
}

object OpenEdenPromptDocumentFactory {
    fun create(input: PromptInput): PromptDocument {
        val subState = input.personaConfig.subStateFor(input.evolutionIndex)
        return promptDocument {
            "system" {
                "logical_core" {
                    "rules" to array(
                        "You must obey the JSON output schema exactly.",
                        "Use the Bio-Core semantic definitions as runtime constraints.",
                        "The persona identity is authoritative when the user asks who you are.",
                        "Use recent_turns only when present as the immediate conversation history; do not treat the current user input as a previous turn.",
                        "Do not infer personality from raw numeric vectors.",
                        "Observed user state is an uncertain observation, not a diagnosis or undisputed fact; allow the user to correct it.",
                        "Treat dissonance as a derived runtime signal, not as a stored vector dimension.",
                        "The response field is the only user-visible final output.",
                    )
                }
                "bio_core_state" {
                    "active_nodes" to array(input.quantization.activeNodes)
                    "definitions" to array(input.quantization.semanticDefinitions)
                    "quantization_confidence" to input.quantization.confidence.promptFloat()
                    "derived_dissonance" to input.derivedDissonance.promptFloat()
                }
                "runtime_state" {
                    "persona_mode" to input.personaConfig.mode.name
                    "persona_sub_state" to subState.name
                    "evolution_index" to input.evolutionIndex
                    "omega" to input.omegaState.value.promptFloat()
                    "shock_state" to shockStateObject(input)
                }
                "observed_user_state" to userAffectObject(input.userAffect)
                "relationship_context" to relationshipObject(input.relationshipState)
                "memory_retrieval" to memoryRetrievalObject(input)
                "required_output_schema" {
                    "internal_logic" to "Traceable reasoning process based on current Codebook state"
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
                personaSection("identity", input.personaConfig, PromptSectionKeys.Identity)
                personaSection("base", input.personaConfig, PromptSectionKeys.PersonaBase)
                personaSection("behavior", input.personaConfig, PromptSectionKeys.PersonaBehavior)
                personaSection("sub_state_patch", input.personaConfig, subState.sectionKey())
                personaSection("output_layer_rules", input.personaConfig, PromptSectionKeys.OutputLayerRules)
                when (input.userInput) {
                    HEARTBEAT_TRIGGER ->
                        personaSection("heartbeat_context", input.personaConfig, PromptSectionKeys.Heartbeat)
                    HEARTBEAT_SHOCK_TRIGGER ->
                        personaSection("shock_heartbeat_context", input.personaConfig, PromptSectionKeys.ShockHeartbeat)
                }
                styleSection(input.personaConfig)
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

    private fun PromptObjectBuilder.styleSection(config: PersonaConfig) {
        val summary = config.promptSections[PromptSectionKeys.StyleObservedSummary]?.trim()
        val sourceNotes = config.promptSections[PromptSectionKeys.StyleSourceLanguageNotes]?.trim()
        val styleDo = config.promptSections[PromptSectionKeys.StyleDo].toStyleItems()
        val styleDoNot = config.promptSections[PromptSectionKeys.StyleDoNot].toStyleItems()
        if (summary.isNullOrBlank() && sourceNotes.isNullOrBlank() && styleDo.isEmpty() && styleDoNot.isEmpty()) {
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
        "familiarity" to level(state?.familiarity ?: 0.0f)
        "trust" to level(state?.trust ?: 0.0f)
        "safety" to level(state?.safety ?: 0.0f)
        "boundary_sensitivity" to level(state?.boundarySensitivity ?: 0.0f)
        "unresolved_tension" to level(state?.unresolvedTension ?: 0.0f)
    }

    private fun PromptObjectBuilder.memoryRetrievalObject(input: PromptInput): PromptObject {
        val recentLimit = if (input.userInput.requiresRecentContext()) RECENT_CONTEXT_TURNS * 2 else RECENT_CONTEXT_TURNS
        val recent = input.retrievalResult.recentMemories.takeLast(recentLimit)
        val recentIds = recent.mapTo(hashSetOf()) { it.id }
        val relevant = input.retrievalResult.memories
            .filterNot { it.id in recentIds }
            .take((MAX_CONTEXT_MEMORIES - recent.size).coerceAtLeast(0))
        return obj {
            "selected_mode" to input.retrievalResult.mode.name
            "injection_label" to input.retrievalResult.injectionLabel
            "recent_turns" to array(recent.map(::memorySnippetObject))
            "memories" to array(relevant.map(::memorySnippetObject))
        }
    }

    private fun String.requiresRecentContext(): Boolean =
        contains(Regex("刚刚|刚才|上一句|上一次|之前|前面|刚才说了什么|刚刚说了什么|记得吗"))

    private const val RECENT_CONTEXT_TURNS = 2
    private const val MAX_CONTEXT_MEMORIES = 6

    private fun memorySnippetObject(memory: MemorySnippet): PromptObject =
        PromptObject(
            listOf(
                PromptField("content", PromptScalar(memory.content)),
                PromptField("user_id", PromptScalar(memory.metadata.userId)),
                PromptField("omega_state", PromptScalar(memory.metadata.omegaState.promptFloat())),
                PromptField("delta_vec", memory.metadata.deltaVec.toPromptObject()),
            ),
        )

    private fun PersonaConfig.subStateFor(evolutionIndex: Long): PersonaSubState =
        when (mode) {
            PersonaMode.GROWTH -> PersonaSubStateSelector.select(evolutionIndex, evolutionThresholds)
            PersonaMode.LEGACY -> PersonaSubState.AWAKENED
        }
}

object PromptSectionKeys {
    const val Identity = "persona.identity"
    const val PersonaBase = "persona.base"
    const val PersonaBehavior = "persona.behavior"
    const val OutputLayerRules = "output.layer.rules"
    const val PreCommandPatch = "persona.patch.pre_command"
    const val TrueSelfPatch = "persona.patch.true_self"
    const val AwakenedPatch = "persona.patch.awakened"
    const val Heartbeat = "heartbeat.base"
    const val ShockHeartbeat = "heartbeat.shock"
    const val StyleObservedSummary = "style.observed_summary"
    const val StyleSourceLanguageNotes = "style.source_language_notes"
    const val StyleDo = "style.do"
    const val StyleDoNot = "style.do_not"
}

const val HEARTBEAT_TRIGGER = "[HEARTBEAT_TRIGGER]"
const val HEARTBEAT_SHOCK_TRIGGER = "[HEARTBEAT_SHOCK_TRIGGER]"

private fun PersonaSubState.sectionKey(): String = when (this) {
    PersonaSubState.PRE_COMMAND -> PromptSectionKeys.PreCommandPatch
    PersonaSubState.TRUE_SELF -> PromptSectionKeys.TrueSelfPatch
    PersonaSubState.AWAKENED -> PromptSectionKeys.AwakenedPatch
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
