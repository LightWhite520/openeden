package io.openeden.codebook

import io.openeden.bio.BioVector
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DjlVqVaeCodebookModelRunnerTest {
    @Test
    fun `adapter predicts top k nodes through isolated predictor`() = runTest {
        val runner = DjlVqVaeCodebookModelRunner(
            predictor = object : DjlFloatPredictor {
                override fun predict(input: FloatArray): FloatArray = floatArrayOf(1.0f, 0.0f)
                override fun close() = Unit
            },
            inputDimension = 8,
            codebook = listOf(
                CodebookVector("NODE_A", listOf(1.0f, 0.0f)),
                CodebookVector("NODE_B", listOf(0.0f, 1.0f)),
            ),
            topK = 1,
        )

        val result = runner.predict(BioVector.Neutral, 0.0f)

        assertEquals(listOf("NODE_A"), result.nodeIds)
        assertEquals(1.0f, result.confidence, 0.0001f)
        runner.close()
    }

    @Test
    fun `adapter rejects a malformed predictor output`() = runTest {
        val runner = DjlVqVaeCodebookModelRunner(
            predictor = object : DjlFloatPredictor {
                override fun predict(input: FloatArray): FloatArray = floatArrayOf(Float.NaN)
                override fun close() = Unit
            },
            inputDimension = 8,
            codebook = listOf(CodebookVector("NODE_A", listOf(1.0f))),
        )

        assertFailsWith<IllegalArgumentException> {
            runner.predict(BioVector.Neutral, 0.0f)
        }
        runner.close()
    }
}
