package io.openeden.persona

data class PersonaConfig(
    val mode: PersonaMode,
    val evolutionThresholds: EvolutionThresholds,
    val promptSections: Map<String, String>,
)
