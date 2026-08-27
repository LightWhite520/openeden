package io.openeden.llm

data class OpenAiProviderCapabilities(
    val basicResponses: Boolean,
    val cacheKeyAccepted: Boolean,
    val cacheOptionsAccepted: Boolean,
    val explicitBreakpointAccepted: Boolean,
    val previousResponseAccepted: Boolean,
    val metricAvailability: CacheMetricAvailability,
    val expiresAtMs: Long,
)

data class OpenAiCapabilityCacheKey(
    val baseUrl: String,
    val model: String,
    val routingFingerprint: String,
)
