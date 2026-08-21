package io.openeden.llm

data class LlmCacheMetrics(
    val inputTokens: Long,
    val cachedInputTokens: Long,
    val requestCount: Int = 1,
) {
    init {
        require(inputTokens >= 0L) { "inputTokens must not be negative" }
        require(cachedInputTokens in 0L..inputTokens) { "cachedInputTokens must be within inputTokens" }
        require(requestCount > 0) { "requestCount must be positive" }
    }

    val uncachedInputTokens: Long get() = inputTokens - cachedInputTokens
    val cacheHitRate: Double get() = if (inputTokens == 0L) 0.0 else cachedInputTokens.toDouble() / inputTokens

    fun traceAttributes(): Map<String, String> = mapOf(
        "input_count" to inputTokens.toString(),
        "cached_input_count" to cachedInputTokens.toString(),
        "uncached_input_count" to uncachedInputTokens.toString(),
        "cache_hit_rate" to cacheHitRate.toString(),
        "request_count" to requestCount.toString(),
    )

    companion object {
        fun aggregate(metrics: List<LlmCacheMetrics>): LlmCacheMetrics {
            require(metrics.isNotEmpty()) { "Cannot aggregate empty cache metrics" }
            return LlmCacheMetrics(
                inputTokens = metrics.sumOf { it.inputTokens },
                cachedInputTokens = metrics.sumOf { it.cachedInputTokens },
                requestCount = metrics.sumOf { it.requestCount },
            )
        }
    }
}
