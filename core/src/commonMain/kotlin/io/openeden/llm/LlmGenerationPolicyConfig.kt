package io.openeden.llm

data class LlmGenerationPolicyConfig(
    val temperatureMin: Float = 0.2f,
    val temperatureMax: Float = 1.0f,
    val maxOutputTokens: Int? = null,
) {
    init {
        require(temperatureMin.isFinite() && temperatureMin in 0.0f..2.0f) {
            "temperatureMin must be finite and in [0, 2]"
        }
        require(temperatureMax.isFinite() && temperatureMax in 0.0f..2.0f) {
            "temperatureMax must be finite and in [0, 2]"
        }
        require(temperatureMin <= temperatureMax) {
            "temperatureMin must not exceed temperatureMax"
        }
        require(maxOutputTokens == null || maxOutputTokens > 0) {
            "maxOutputTokens must be null or positive"
        }
    }

    fun staticSettings(): LlmGenerationSettings = LlmGenerationSettings(
        temperature = (temperatureMin + temperatureMax) / 2.0f,
        verbosity = LlmVerbosity.MEDIUM,
        maxOutputTokens = maxOutputTokens,
    )

    companion object {
        val Default = LlmGenerationPolicyConfig()
    }
}
