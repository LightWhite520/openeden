package io.openeden.llm

import kotlin.test.Test
import kotlin.test.assertEquals

class LlmCacheMetricsTest {
    @Test
    fun `aggregates cache hit rate by token count`() {
        val metrics = LlmCacheMetrics.aggregate(
            listOf(
                LlmCacheMetrics(inputTokens = 4_000, cachedInputTokens = 3_000),
                LlmCacheMetrics(inputTokens = 5_000, cachedInputTokens = 3_500),
            ),
        )

        assertEquals(9_000, metrics.inputTokens)
        assertEquals(6_500, metrics.cachedInputTokens)
        assertEquals(2_500, metrics.uncachedInputTokens)
        assertEquals(6_500.0 / 9_000.0, metrics.cacheHitRate, 0.000001)
        assertEquals(2, metrics.requestCount)
    }
}
