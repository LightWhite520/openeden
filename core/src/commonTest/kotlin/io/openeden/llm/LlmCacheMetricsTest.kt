package io.openeden.llm

import io.openeden.prompt.BuiltPrompt
import io.openeden.prompt.PromptManifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class LlmCacheMetricsTest {
    @Test
    fun `manifest records hashes and sizes without prompt text`() {
        val manifest = PromptManifest.from(BuiltPrompt("system secret", "persona", "user", "context"))

        assertEquals(listOf("system", "persona", "context", "user"), manifest.entries.map { it.id })
        assertFalse(manifest.toString().contains("system secret"))
    }

    @Test
    fun `unreported provider usage remains unobservable`() {
        assertEquals(CacheMetricAvailability.UNOBSERVABLE, LlmCacheMetrics.Unobservable.availability)
    }

    @Test
    fun `aggregates cache hit rate by token count`() {
        val metrics = LlmCacheMetrics.aggregate(
            listOf(
                LlmCacheMetrics(inputTokens = 4_000, cachedInputTokens = 3_000),
                LlmCacheMetrics(inputTokens = 5_000, cachedInputTokens = 3_500, cacheWriteTokens = 500),
            ),
        )

        assertEquals(9_000, metrics.inputTokens)
        assertEquals(6_500, metrics.cachedInputTokens)
        assertEquals(2_500, metrics.uncachedInputTokens)
        assertEquals(500, metrics.cacheWriteTokens)
        assertEquals(2_000, metrics.ordinaryInputTokens)
        assertEquals(6_500.0 / 9_000.0, metrics.cacheHitRate, 0.000001)
        assertEquals(2, metrics.requestCount)
    }

    @Test
    fun `rejects cache writes that overlap or exceed input tokens`() {
        assertFailsWith<IllegalArgumentException> {
            LlmCacheMetrics(inputTokens = 100, cachedInputTokens = 80, cacheWriteTokens = 21)
        }
    }
}
