package io.openeden.llm

import java.net.URI

private val gptModelPattern = Regex("^gpt-(\\d+)(?:\\.(\\d+))?", RegexOption.IGNORE_CASE)

enum class OpenAiPromptCachingMode {
    AUTO,
    EXPLICIT,
    DISABLED,
    ;

    companion object {
        fun parse(raw: String): OpenAiPromptCachingMode = when (raw.trim().lowercase()) {
            "auto" -> AUTO
            "explicit" -> EXPLICIT
            "disabled", "off" -> DISABLED
            else -> throw IllegalArgumentException("Unsupported OpenAI prompt caching mode: $raw")
        }
    }
}

fun OpenAiPromptCachingMode.usesCache(): Boolean = this != OpenAiPromptCachingMode.DISABLED

fun OpenAiPromptCachingMode.usesExplicitCacheOptions(model: String): Boolean = when (this) {
    OpenAiPromptCachingMode.DISABLED -> false
    OpenAiPromptCachingMode.EXPLICIT -> true
    OpenAiPromptCachingMode.AUTO -> supportsExplicitPromptCaching(model)
}

fun OpenAiPromptCachingMode.usesExplicitBreakpoint(model: String): Boolean =
    usesExplicitBreakpoint(model, defaultOpenAiBaseUrl)

fun OpenAiPromptCachingMode.usesExplicitBreakpoint(model: String, baseUrl: String): Boolean = when (this) {
    OpenAiPromptCachingMode.DISABLED -> false
    OpenAiPromptCachingMode.EXPLICIT -> true
    OpenAiPromptCachingMode.AUTO -> supportsExplicitPromptCaching(model) && isOfficialOpenAiBaseUrl(baseUrl)
}

private fun isOfficialOpenAiBaseUrl(baseUrl: String): Boolean =
    runCatching {
        val uri = URI(baseUrl.trim())
        uri.scheme.equals("https", ignoreCase = true) && uri.host.equals("api.openai.com", ignoreCase = true)
    }
        .getOrDefault(false)

private const val defaultOpenAiBaseUrl = "https://api.openai.com/v1"

fun supportsExplicitPromptCaching(model: String): Boolean {
    val version = gptModelPattern.find(model.trim())
        ?: return false
    val major = version.groupValues[1].toIntOrNull() ?: return false
    val minor = version.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
    return major > 5 || (major == 5 && minor >= 6)
}
