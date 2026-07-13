package io.openeden.runtime

data class OmegaState(val value: Float) {
    init {
        require(value in 0.0f..1.0f)
    }

    fun increase(amount: Float): OmegaState = OmegaState((value + amount).coerceIn(value, 1.0f))
}
