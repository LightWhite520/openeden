package io.openeden.server.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class LlmCacheMetricsDto(
    val inputTokens: Long,
    val cachedInputTokens: Long,
    val uncachedInputTokens: Long,
    val ordinaryInputTokens: Long,
    val cacheWriteTokens: Long,
    val cacheHitRate: Double,
    val cacheHitRequestCount: Int,
    val requestHitRate: Double,
    val requestCount: Int,
)
