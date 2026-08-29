package io.openeden.llm

enum class OpenAiPromptCachingMode {
    OFFICIAL_EXPLICIT,
    RELAY_APPEND_ONLY,
    OBSERVE_ONLY,
    CACHE_DISABLED,
    ;

    companion object {
        fun parse(raw: String): OpenAiPromptCachingMode = when (raw.trim().lowercase()) {
            "official_explicit" -> OFFICIAL_EXPLICIT
            "relay_append_only" -> RELAY_APPEND_ONLY
            "observe_only" -> OBSERVE_ONLY
            "cache_disabled", "disabled", "off" -> CACHE_DISABLED
            else -> throw IllegalArgumentException("Unsupported OpenAI cache policy: $raw")
        }
    }
}

typealias OpenAiCachePolicy = OpenAiPromptCachingMode

internal data class OpenAiRequestCacheMetadata(
    val cacheKey: Boolean,
    val cacheOptions: Boolean,
    val breakpoint: Boolean,
) {
    val isPresent: Boolean get() = cacheKey || cacheOptions || breakpoint

    companion object {
        val None = OpenAiRequestCacheMetadata(false, false, false)
    }
}

internal fun OpenAiCachePolicy.requestMetadata(
    capabilities: OpenAiProviderCapabilities,
): OpenAiRequestCacheMetadata = when (this) {
    OpenAiCachePolicy.OFFICIAL_EXPLICIT -> OpenAiRequestCacheMetadata(
        cacheKey = capabilities.cacheKeyAccepted,
        cacheOptions = capabilities.cacheOptionsAccepted,
        breakpoint = capabilities.explicitBreakpointAccepted,
    )

    OpenAiCachePolicy.RELAY_APPEND_ONLY -> OpenAiRequestCacheMetadata(
        cacheKey = capabilities.cacheKeyAccepted,
        cacheOptions = false,
        breakpoint = false,
    )

    OpenAiCachePolicy.OBSERVE_ONLY,
    OpenAiCachePolicy.CACHE_DISABLED,
    -> OpenAiRequestCacheMetadata.None
}

internal fun OpenAiCachePolicy.observesUsage(): Boolean = this != OpenAiCachePolicy.CACHE_DISABLED
