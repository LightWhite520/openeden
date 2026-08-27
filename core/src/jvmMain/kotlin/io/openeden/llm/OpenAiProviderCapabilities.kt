package io.openeden.llm

data class OpenAiProviderCapabilities(
    val basicResponses: Boolean,
    val cacheKeyAccepted: Boolean,
    val cacheOptionsAccepted: Boolean,
    val explicitBreakpointAccepted: Boolean,
    val previousResponseAccepted: Boolean,
    val metricAvailability: CacheMetricAvailability,
    val expiresAtMs: Long,
) {
    companion object {
        fun unavailable(nowMs: Long): OpenAiProviderCapabilities = OpenAiProviderCapabilities(
            basicResponses = false,
            cacheKeyAccepted = false,
            cacheOptionsAccepted = false,
            explicitBreakpointAccepted = false,
            previousResponseAccepted = false,
            metricAvailability = CacheMetricAvailability.UNOBSERVABLE,
            expiresAtMs = nowMs,
        )
    }
}

data class OpenAiCapabilityCacheKey(
    val baseUrl: String,
    val model: String,
    val routingFingerprint: String,
)

fun interface OpenAiCapabilityProvider {
    suspend fun capabilities(): OpenAiProviderCapabilities
}
