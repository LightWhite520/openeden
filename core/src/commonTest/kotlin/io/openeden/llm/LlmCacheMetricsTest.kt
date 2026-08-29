package io.openeden.llm

import io.openeden.prompt.PromptManifest
import io.openeden.prompt.PromptSegmentKind
import io.openeden.prompt.testBuiltPrompt
import io.openeden.transcript.PromptHistorySnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class LlmCacheMetricsTest {
    @Test
    fun `manifest records hashes and sizes without prompt text`() {
        val manifest = PromptManifest.from(
            testBuiltPrompt(
                PromptSegmentKind.SYSTEM_CONTRACT to "system secret",
                PromptSegmentKind.PERSONA to "persona",
                PromptSegmentKind.BIO to "context",
                PromptSegmentKind.USER to "user",
            ),
        )

        assertEquals(
            listOf("system_contract", "persona", "incarnation_anchor", "history"),
            manifest.entries.map { it.id },
        )
        assertFalse(manifest.toString().contains("system secret"))
        assertFalse(manifest.toString().contains("context"))
        assertFalse(manifest.toString().contains("user"))
    }

    @Test
    fun `dynamic suffix cannot change manifest or cache identity`() {
        val first = testBuiltPrompt(
            PromptSegmentKind.SYSTEM_CONTRACT to "stable system",
            PromptSegmentKind.PERSONA to "stable persona",
            PromptSegmentKind.INCARNATION_ANCHOR to "stable incarnation",
            PromptSegmentKind.BIO to "NODE_001",
            PromptSegmentKind.RELATIONSHIP to "relationship one",
            PromptSegmentKind.RAG to "memory one",
            PromptSegmentKind.TEMPORAL to "2026-08-29 10:01",
            PromptSegmentKind.USER to "first user",
        )
        val changed = testBuiltPrompt(
            PromptSegmentKind.SYSTEM_CONTRACT to "stable system",
            PromptSegmentKind.PERSONA to "stable persona",
            PromptSegmentKind.INCARNATION_ANCHOR to "stable incarnation",
            PromptSegmentKind.BIO to "NODE_999",
            PromptSegmentKind.RELATIONSHIP to "relationship two",
            PromptSegmentKind.RAG to "memory two",
            PromptSegmentKind.TEMPORAL to "2026-08-29 10:02",
            PromptSegmentKind.USER to "second user",
        )

        assertEquals(PromptManifest.from(first), PromptManifest.from(changed))
        assertEquals(first.cacheIdentity, changed.cacheIdentity)
    }

    @Test
    fun `stable prefix or history epoch rotates cache identity and manifest`() {
        val original = testBuiltPrompt(
            PromptSegmentKind.SYSTEM_CONTRACT to "stable system",
            PromptSegmentKind.PERSONA to "stable persona",
            PromptSegmentKind.INCARNATION_ANCHOR to "stable incarnation",
        )
        val stableChanged = testBuiltPrompt(
            PromptSegmentKind.SYSTEM_CONTRACT to "stable system changed",
            PromptSegmentKind.PERSONA to "stable persona",
            PromptSegmentKind.INCARNATION_ANCHOR to "stable incarnation",
        )
        val epochChanged = testBuiltPrompt(
            PromptSegmentKind.SYSTEM_CONTRACT to "stable system",
            PromptSegmentKind.PERSONA to "stable persona",
            PromptSegmentKind.INCARNATION_ANCHOR to "stable incarnation",
            promptHistory = PromptHistorySnapshot(cacheEpoch = 2L),
        )

        assertNotEquals(original.cacheIdentity, stableChanged.cacheIdentity)
        assertNotEquals(original.cacheIdentity, epochChanged.cacheIdentity)
        assertNotEquals(PromptManifest.from(original), PromptManifest.from(stableChanged))
        assertNotEquals(PromptManifest.from(original), PromptManifest.from(epochChanged))
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
