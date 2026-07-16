package io.openeden.persona

object MapPersonaLoader {
    private val requiredPromptSections = listOf(
        "persona.base",
        "output.layer.rules",
        "persona.patch.pre_command",
        "persona.patch.true_self",
        "persona.patch.awakened",
        "heartbeat.base",
        "heartbeat.shock",
        "diary.narrative",
    )

    // Distilled behavior + style guidance. Optional so personas without it (e.g. default.yaml) stay valid.
    private val optionalPromptSections = listOf(
        "persona.identity",
        "persona.behavior",
        "style.observed_summary",
        "style.source_language_notes",
        "style.do",
        "style.do_not",
        "style.generation_mechanics",
        "style.signature_examples",
        "style.stage_examples.pre_command",
        "style.stage_examples.true_self",
        "style.stage_examples.awakened",
    )

    fun load(values: Map<String, String>): PersonaConfig {
        val mode = when (val rawMode = values.required("mode").lowercase()) {
            "growth" -> PersonaMode.GROWTH
            "legacy" -> PersonaMode.LEGACY
            else -> throw IllegalArgumentException("Unsupported persona mode: $rawMode")
        }
        val requestedStartSubState = values.required("start_sub_state").parseSubState()
        val startSubState = when (mode) {
            PersonaMode.GROWTH -> requestedStartSubState
            PersonaMode.LEGACY -> {
                require(requestedStartSubState == PersonaSubState.AWAKENED) {
                    "Legacy persona mode only supports the awakened starting point"
                }
                PersonaSubState.AWAKENED
            }
        }
        val sections = buildMap {
            requiredPromptSections.forEach { key -> put(key, values.required(key)) }
            optionalPromptSections.forEach { key ->
                values[key]?.takeIf { it.isNotBlank() }?.let { put(key, it) }
            }
        }
        return PersonaConfig(mode = mode, startSubState = startSubState, promptSections = sections)
    }

    private fun String.parseSubState(): PersonaSubState = when (lowercase()) {
        "pre_command" -> PersonaSubState.PRE_COMMAND
        "true_self" -> PersonaSubState.TRUE_SELF
        "awakened" -> PersonaSubState.AWAKENED
        else -> throw IllegalArgumentException("Unsupported persona start_sub_state: $this")
    }

    private fun Map<String, String>.required(key: String): String =
        get(key)?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Missing required persona field: $key")
}
