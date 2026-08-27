package io.openeden.llm

data class LlmCacheMetrics(
    val inputTokens: Long,
    val cachedInputTokens: Long,
    val cacheWriteTokens: Long = 0L,
    val availability: CacheMetricAvailability = CacheMetricAvailability.REPORTED,
    val requestCount: Int = 1,
    val cacheHitRequestCount: Int = if (cachedInputTokens > 0L) 1 else 0,
) {
    init {
        require(inputTokens >= 0L) { "inputTokens must not be negative" }
        require(cachedInputTokens in 0L..inputTokens) { "cachedInputTokens must be within inputTokens" }
        require(cacheWriteTokens in 0L..inputTokens) { "cacheWriteTokens must be within inputTokens" }
        require(cachedInputTokens + cacheWriteTokens <= inputTokens) {
            "cachedInputTokens and cacheWriteTokens must not exceed inputTokens"
        }
        require(requestCount >= 0) { "requestCount must not be negative" }
        require(cacheHitRequestCount in 0..requestCount) { "cacheHitRequestCount must be within requestCount" }
    }

    val uncachedInputTokens: Long get() = inputTokens - cachedInputTokens
    val ordinaryInputTokens: Long get() = inputTokens - cachedInputTokens - cacheWriteTokens
    val cacheHitRate: Double get() = if (inputTokens == 0L) 0.0 else cachedInputTokens.toDouble() / inputTokens
    val requestHitRate: Double get() = if (requestCount == 0) 0.0 else cacheHitRequestCount.toDouble() / requestCount

    fun traceAttributes(): Map<String, String> = mapOf(
        "input_count" to inputTokens.toString(),
        "cached_input_count" to cachedInputTokens.toString(),
        "cache_write_count" to cacheWriteTokens.toString(),
        "uncached_input_count" to uncachedInputTokens.toString(),
        "ordinary_input_count" to ordinaryInputTokens.toString(),
        "cache_hit_rate" to cacheHitRate.toString(),
        "cache_request_hit_count" to cacheHitRequestCount.toString(),
        "cache_request_hit_rate" to requestHitRate.toString(),
        "request_count" to requestCount.toString(),
        "availability" to availability.name,
    )

    companion object {
        fun aggregate(metrics: List<LlmCacheMetrics>): LlmCacheMetrics {
            require(metrics.isNotEmpty()) { "Cannot aggregate empty cache metrics" }
            return LlmCacheMetrics(
                inputTokens = metrics.sumOf { it.inputTokens },
                cachedInputTokens = metrics.sumOf { it.cachedInputTokens },
                cacheWriteTokens = metrics.sumOf { it.cacheWriteTokens },
                availability = if (metrics.all { it.availability == CacheMetricAvailability.REPORTED }) {
                    CacheMetricAvailability.REPORTED
                } else {
                    CacheMetricAvailability.UNOBSERVABLE
                },
                requestCount = metrics.sumOf { it.requestCount },
                cacheHitRequestCount = metrics.sumOf { it.cacheHitRequestCount },
            )
        }

        val Unobservable = LlmCacheMetrics(
            inputTokens = 0,
            cachedInputTokens = 0,
            cacheWriteTokens = 0,
            availability = CacheMetricAvailability.UNOBSERVABLE,
            requestCount = 0,
            cacheHitRequestCount = 0,
        )
    }
}
