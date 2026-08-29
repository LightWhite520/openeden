package io.openeden.llm

import java.net.URI

private val gptModelPattern = Regex("^gpt-(\\d+)(?:\\.(\\d+))?", RegexOption.IGNORE_CASE)

enum class OpenAiPromptCachingMode {
    AUTO,
    EXPLICIT,
    DISABLED,
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
            "cache_disabled", "off" -> CACHE_DISABLED
            "auto" -> AUTO
            "explicit" -> EXPLICIT
            "disabled" -> DISABLED
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

    OpenAiCachePolicy.AUTO -> OpenAiRequestCacheMetadata(
        cacheKey = capabilities.cacheKeyAccepted,
        cacheOptions = false,
        breakpoint = false,
    )

    OpenAiCachePolicy.EXPLICIT -> OpenAiRequestCacheMetadata(
        cacheKey = capabilities.cacheKeyAccepted,
        cacheOptions = capabilities.cacheOptionsAccepted,
        breakpoint = capabilities.explicitBreakpointAccepted,
    )

    OpenAiCachePolicy.OBSERVE_ONLY,
    OpenAiCachePolicy.CACHE_DISABLED,
    OpenAiCachePolicy.DISABLED,
    -> OpenAiRequestCacheMetadata.None
}

internal fun OpenAiCachePolicy.requestsCapabilityProbe(): Boolean = when (this) {
    OpenAiCachePolicy.OFFICIAL_EXPLICIT,
    OpenAiCachePolicy.RELAY_APPEND_ONLY,
    OpenAiCachePolicy.AUTO,
    OpenAiCachePolicy.EXPLICIT,
    -> true

    OpenAiCachePolicy.OBSERVE_ONLY,
    OpenAiCachePolicy.CACHE_DISABLED,
    OpenAiCachePolicy.DISABLED,
    -> false
}

internal fun OpenAiCachePolicy.observesUsage(): Boolean = when (this) {
    OpenAiCachePolicy.CACHE_DISABLED,
    OpenAiCachePolicy.DISABLED,
    -> false

    else -> true
}

@Deprecated("Use an explicit cache policy and capability evidence")
fun OpenAiPromptCachingMode.usesCache(): Boolean = when (this) {
    OpenAiPromptCachingMode.DISABLED,
    OpenAiPromptCachingMode.CACHE_DISABLED,
    -> false

    else -> true
}

@Deprecated("Use requestMetadata with provider capabilities")
fun OpenAiPromptCachingMode.usesExplicitCacheOptions(model: String): Boolean = when (this) {
    OpenAiPromptCachingMode.EXPLICIT,
    OpenAiPromptCachingMode.OFFICIAL_EXPLICIT,
    -> true

    OpenAiPromptCachingMode.AUTO -> supportsExplicitPromptCaching(model)
    else -> false
}

@Deprecated("Use requestMetadata with provider capabilities")
fun OpenAiPromptCachingMode.usesExplicitBreakpoint(model: String): Boolean =
    usesExplicitBreakpoint(model, DEFAULT_OPENAI_BASE_URL)

@Deprecated("Use requestMetadata with provider capabilities")
fun OpenAiPromptCachingMode.usesExplicitBreakpoint(model: String, baseUrl: String): Boolean = when (this) {
    OpenAiPromptCachingMode.EXPLICIT,
    OpenAiPromptCachingMode.OFFICIAL_EXPLICIT,
    -> true

    OpenAiPromptCachingMode.AUTO -> supportsExplicitPromptCaching(model) && isOfficialOpenAiBaseUrl(baseUrl)
    else -> false
}

private fun isOfficialOpenAiBaseUrl(baseUrl: String): Boolean =
    runCatching {
        val uri = URI(baseUrl.trim())
        uri.scheme.equals("https", ignoreCase = true) && uri.host.equals("api.openai.com", ignoreCase = true)
    }.getOrDefault(false)

private const val DEFAULT_OPENAI_BASE_URL = "https://api.openai.com/v1"

@Deprecated("Use provider capability evidence")
fun supportsExplicitPromptCaching(model: String): Boolean {
    val version = gptModelPattern.find(model.trim()) ?: return false
    val major = version.groupValues[1].toIntOrNull() ?: return false
    val minor = version.groupValues[2].takeIf(String::isNotEmpty)?.toIntOrNull() ?: 0
    return major > 5 || (major == 5 && minor >= 6)
}
