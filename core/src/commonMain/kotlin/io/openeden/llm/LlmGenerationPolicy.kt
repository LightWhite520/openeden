package io.openeden.llm

import io.openeden.bio.InternalBioVector

object LlmGenerationPolicy {
    fun resolve(
        internalVector: InternalBioVector,
        config: LlmGenerationPolicyConfig,
    ): LlmGenerationSettings {
        val divergence = ((internalVector.s - internalVector.l + 2.0f) / 4.0f).coerceIn(0.0f, 1.0f)
        val temperature = (
            config.temperatureMin +
                (config.temperatureMax - config.temperatureMin) * divergence
            ).coerceIn(config.temperatureMin, config.temperatureMax)
        val verbosity = when {
            internalVector.v <= -0.35f -> LlmVerbosity.LOW
            internalVector.v >= 0.35f -> LlmVerbosity.HIGH
            else -> LlmVerbosity.MEDIUM
        }
        return LlmGenerationSettings(
            temperature = temperature,
            verbosity = verbosity,
            maxOutputTokens = config.maxOutputTokens,
        )
    }
}
