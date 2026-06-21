package io.openeden.codebook

import io.openeden.bio.BioVector
import io.openeden.trace.TraceTag
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class VqVaeCodebookQuantizerTest {
    private val dictionary = CodebookDictionary.parseCsv(
        """
        node_id,definition,tags
        NODE_001,"calm semantic state","stable"
        NODE_002,"high resonance state","pathos"
        """.trimIndent(),
    )

    @Test
    fun `uses model nodes and csv definitions when confidence is high`() = runTest {
        val quantizer = VqVaeCodebookQuantizer(
            modelRunner = object : CodebookModelRunner {
                override suspend fun predict(vector: BioVector, dissonance: Float): CodebookModelResult =
                    CodebookModelResult(listOf("NODE_001", "NODE_002"), confidence = 0.91f)
            },
            dictionary = dictionary,
        )

        val result = quantizer.quantize(BioVector.Neutral, 0.0f)

        assertEquals(listOf("NODE_001", "NODE_002"), result.activeNodes)
        assertEquals(listOf("calm semantic state", "high resonance state"), result.semanticDefinitions)
        assertContains(result.traceTags, TraceTag.CodebookQuantized)
    }

    @Test
    fun `falls back when model confidence is low`() = runTest {
        val quantizer = VqVaeCodebookQuantizer(
            modelRunner = object : CodebookModelRunner {
                override suspend fun predict(vector: BioVector, dissonance: Float): CodebookModelResult =
                    CodebookModelResult(listOf("NODE_001"), confidence = 0.2f)
            },
            dictionary = dictionary,
        )

        val result = quantizer.quantize(BioVector.Neutral, 0.0f)

        assertEquals(listOf("HEURISTIC_FALLBACK"), result.activeNodes)
        assertContains(result.traceTags, TraceTag.CodebookHeuristicFallback)
    }

    @Test
    fun `falls back when model throws`() = runTest {
        val quantizer = VqVaeCodebookQuantizer(
            modelRunner = object : CodebookModelRunner {
                override suspend fun predict(vector: BioVector, dissonance: Float): CodebookModelResult =
                    error("model unavailable")
            },
            dictionary = dictionary,
        )

        val result = quantizer.quantize(BioVector.Neutral, 0.0f)

        assertEquals(listOf("HEURISTIC_FALLBACK"), result.activeNodes)
        assertContains(result.traceTags, TraceTag.CodebookHeuristicFallback)
    }
}
