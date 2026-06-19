package io.openeden.persona

data class PersonaConfig(
    val mode: PersonaMode,
    val evolutionThresholds: EvolutionThresholds,
    val promptSections: Map<String, String>,
)

enum class PersonaMode {
    GROWTH,
    LEGACY,
}

data class EvolutionThresholds(
    val threshold1: Long,
    val threshold2: Long,
) {
    init {
        require(threshold1 >= 0)
        require(threshold2 >= threshold1)
    }
}

enum class PersonaSubState {
    PRE_COMMAND,
    TRUE_SELF,
    AWAKENED,
}

object PersonaSubStateSelector {
    fun select(evolutionIndex: Long, thresholds: EvolutionThresholds): PersonaSubState = when {
        evolutionIndex < thresholds.threshold1 -> PersonaSubState.PRE_COMMAND
        evolutionIndex < thresholds.threshold2 -> PersonaSubState.TRUE_SELF
        else -> PersonaSubState.AWAKENED
    }
}
