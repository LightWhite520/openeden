package io.openeden.llm

data class LlmGenerationSettings(
    val temperature: Float,
    val verbosity: LlmVerbosity,
    val maxOutputTokens: Int? = null,
) {
    init {
        require(temperature.isFinite() && temperature in 0.0f..2.0f) {
            "temperature must be finite and in [0, 2]"
        }
        require(maxOutputTokens == null || maxOutputTokens > 0) {
            "maxOutputTokens must be null or positive"
        }
    }

    companion object {
        val Default = LlmGenerationSettings(
            temperature = 0.6f,
            verbosity = LlmVerbosity.MEDIUM,
        )
    }
}
