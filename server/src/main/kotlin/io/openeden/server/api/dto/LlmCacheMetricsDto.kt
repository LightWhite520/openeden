package io.openeden.server.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class LlmCacheMetricsDto(
    val inputTokens: Long,
    val cachedInputTokens: Long,
    val uncachedInputTokens: Long,
    val cacheHitRate: Double,
    val requestCount: Int,
)
