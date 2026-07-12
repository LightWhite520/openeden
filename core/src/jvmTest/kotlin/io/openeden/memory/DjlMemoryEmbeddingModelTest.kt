package io.openeden.memory

import io.openeden.bio.BioVector
import io.openeden.codebook.DjlFloatPredictor
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DjlMemoryEmbeddingModelTest {
    @Test
    fun `text and emotional embeddings are normalized through DJL predictors`() = runTest {
        val model = DjlMemoryEmbeddingModel(
            textPredictor = constantPredictor(floatArrayOf(3.0f, 4.0f)),
            emotionalPredictor = constantPredictor(FloatArray(8) { 1.0f }),
            textInputDimension = 4,
        )

        assertEquals(listOf(0.6f, 0.8f), model.embed("hello"))
        assertEquals(1.0f, model.embed(BioVector.Neutral).fold(0.0f) { total, value -> total + value * value }, 0.0001f)
        model.close()
    }

    @Test
    fun `zero DJL embedding output is rejected`() = runTest {
        val model = DjlMemoryEmbeddingModel(
            textPredictor = constantPredictor(floatArrayOf(0.0f, 0.0f)),
            emotionalPredictor = constantPredictor(FloatArray(8) { 1.0f }),
            textInputDimension = 4,
        )
        assertFailsWith<IllegalArgumentException> { model.embed("hello") }
        model.close()
    }

    private fun constantPredictor(value: FloatArray) = object : DjlFloatPredictor {
        override fun predict(input: FloatArray): FloatArray = value.copyOf()
        override fun close() = Unit
    }
}
