package io.openeden.codebook


import io.openeden.bio.BioVector
import io.openeden.trace.TraceTag
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HeuristicCodebookFallbackTest {
    @Test
    fun `heuristic fallback emits deterministic definitions and trace tag`() = runTest {
        val quantizer = HeuristicCodebookFallback()
        val vector = BioVector(
            l = 0.7f,
            p = 0.2f,
            e = 0.7f,
            s = 0.8f,
            tau = 0.1f,
            v = 0.1f,
            m = 0.7f,
            f = 0.4f,
        )

        val result = quantizer.quantize(vector, vector.derivedDissonance())

        assertEquals(listOf("HEURISTIC_FALLBACK"), result.activeNodes)
        assertContains(result.traceTags, TraceTag.CodebookHeuristicFallback)
        assertTrue(result.semanticDefinitions.any { it == "Logical clarity: HIGH" })
        assertTrue(result.semanticDefinitions.any { it == "Vitality: EXHAUSTED" })
        assertTrue(result.semanticDefinitions.any { it == "Dissonance (derived): LOW" })
    }
}
