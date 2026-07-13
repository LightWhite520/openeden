package io.openeden.persona

data class EvolutionThresholds(
    val threshold1: Long,
    val threshold2: Long,
) {
    init {
        require(threshold1 >= 0)
        require(threshold2 >= threshold1)
    }
}
