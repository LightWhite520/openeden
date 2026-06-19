package io.openeden.prompt

import io.openeden.memory.MemorySnippet
import io.openeden.memory.RetrievalResult
import io.openeden.persona.PersonaConfig
import io.openeden.persona.PersonaMode
import io.openeden.persona.PersonaSubState
import io.openeden.persona.PersonaSubStateSelector

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
                        "Do not infer personality from raw numeric vectors.",
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
                "memory_retrieval" to memoryRetrievalObject(input.retrievalResult)
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
                personaSection("base", input.personaConfig, PromptSectionKeys.PersonaBase)
                personaSection("sub_state_patch", input.personaConfig, subState.sectionKey())
                personaSection("output_layer_rules", input.personaConfig, PromptSectionKeys.OutputLayerRules)
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

    private fun PromptObjectBuilder.shockStateObject(input: PromptInput): PromptObject =
        obj {
            val shock = input.shockState?.takeIf { it.active }
            "active" to (shock != null)
            if (shock != null) {
                "intensity" to shock.intensity.promptFloat()
                "description" to shock.description
            }
        }

    private fun PromptObjectBuilder.memoryRetrievalObject(retrievalResult: RetrievalResult): PromptObject =
        obj {
            "selected_mode" to retrievalResult.mode.name
            "injection_label" to retrievalResult.injectionLabel
            "memories" to array(retrievalResult.memories.map(::memorySnippetObject))
        }

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
    const val PersonaBase = "persona.base"
    const val OutputLayerRules = "output.layer.rules"
    const val PreCommandPatch = "persona.patch.pre_command"
    const val TrueSelfPatch = "persona.patch.true_self"
    const val AwakenedPatch = "persona.patch.awakened"
    const val Heartbeat = "heartbeat.base"
    const val ShockHeartbeat = "heartbeat.shock"
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
