package io.openeden.server.bootstrap

import io.ktor.server.config.ApplicationConfig
import io.openeden.llm.OpenAiCachePolicy

internal data class OpenAiCacheBootstrapConfig(
    val policy: OpenAiCachePolicy,
    val capabilityProbeEnabled: Boolean,
)

internal fun loadOpenAiCacheBootstrapConfig(config: ApplicationConfig): OpenAiCacheBootstrapConfig {
    val policy = loadOpenAiCachePolicy(config)
    val capabilityProbeEnabled = config.propertyOrNull("openeden.llm.capabilityProbe.enabled")
        ?.getString()
        ?.takeIf(String::isNotBlank)
        ?.equals("true", ignoreCase = true)
        ?: false
    require(policy != OpenAiCachePolicy.OFFICIAL_EXPLICIT || capabilityProbeEnabled) {
        "OPENEDEN_OPENAI_PROMPT_CACHING_POLICY=official_explicit requires " +
            "OPENEDEN_OPENAI_CAPABILITY_PROBE_ENABLED=true"
    }
    return OpenAiCacheBootstrapConfig(policy, capabilityProbeEnabled)
}
