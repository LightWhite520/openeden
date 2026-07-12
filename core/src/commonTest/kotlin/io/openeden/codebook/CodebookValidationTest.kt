package io.openeden.codebook

import io.openeden.bio.BioVector
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CodebookValidationTest {
    @Test
    fun `duplicate csv node ids are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            CodebookDictionary.parseCsv(
                """
                node_id,definition,tags
                NODE_001,first,stable
                NODE_001,duplicate,stable
                """.trimIndent(),
            )
        }
    }

    @Test
    fun `non finite model confidence uses traced fallback`() = runTest {
        val result = VqVaeCodebookQuantizer(
            modelRunner = object : CodebookModelRunner {
                override suspend fun predict(vector: BioVector, dissonance: Float): CodebookModelResult =
                    CodebookModelResult(listOf("NODE_001"), Float.NaN)
            },
            dictionary = CodebookDictionary.parseCsv("node_id,definition,tags\nNODE_001,state,stable"),
        ).quantize(BioVector.Neutral, 0.0f)

        assertTrue(result.traceTags.any { it.startsWith("codebook_fallback_reason=") })
    }
}
