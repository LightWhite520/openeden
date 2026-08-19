package io.openeden.server.bootstrap

import io.ktor.server.config.ApplicationConfig
import io.openeden.llm.LlmGenerationPolicyConfig

internal fun loadLlmGenerationPolicyConfig(config: ApplicationConfig): LlmGenerationPolicyConfig =
    LlmGenerationPolicyConfig(
        temperatureMin = config.floatOrDefault("openeden.llm.temperatureMin", 0.2f),
        temperatureMax = config.floatOrDefault("openeden.llm.temperatureMax", 1.0f),
        maxOutputTokens = config.optionalPositiveInt("openeden.llm.maxOutputTokens"),
    )

private fun ApplicationConfig.floatOrDefault(path: String, default: Float): Float {
    val rawValue = propertyOrNull(path)?.getString() ?: return default
    return rawValue.toFloatOrNull()
        ?: throw IllegalArgumentException("$path must be a float")
}

private fun ApplicationConfig.optionalPositiveInt(path: String): Int? {
    val rawValue = propertyOrNull(path)?.getString()?.takeIf { it.isNotBlank() } ?: return null
    return rawValue.toIntOrNull()
        ?: throw IllegalArgumentException("$path must be a positive integer")
}
