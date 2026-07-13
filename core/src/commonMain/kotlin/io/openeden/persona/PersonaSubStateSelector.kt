package io.openeden.persona

object PersonaSubStateSelector {
    fun select(evolutionIndex: Long, thresholds: EvolutionThresholds): PersonaSubState = when {
        evolutionIndex < thresholds.threshold1 -> PersonaSubState.PRE_COMMAND
        evolutionIndex < thresholds.threshold2 -> PersonaSubState.TRUE_SELF
        else -> PersonaSubState.AWAKENED
    }
}
