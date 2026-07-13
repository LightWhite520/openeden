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
    )

    fun load(values: Map<String, String>): PersonaConfig {
        val mode = when (val rawMode = values.required("mode").lowercase()) {
            "growth" -> PersonaMode.GROWTH
            "legacy" -> PersonaMode.LEGACY
            else -> throw IllegalArgumentException("Unsupported persona mode: $rawMode")
        }
        val thresholds = EvolutionThresholds(
            threshold1 = values.required("evolution.threshold_1").toLong(),
            threshold2 = values.required("evolution.threshold_2").toLong(),
        )
        val sections = buildMap {
            requiredPromptSections.forEach { key -> put(key, values.required(key)) }
            optionalPromptSections.forEach { key ->
                values[key]?.takeIf { it.isNotBlank() }?.let { put(key, it) }
            }
        }
        return PersonaConfig(mode = mode, evolutionThresholds = thresholds, promptSections = sections)
    }

    private fun Map<String, String>.required(key: String): String =
        get(key)?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Missing required persona field: $key")
}
