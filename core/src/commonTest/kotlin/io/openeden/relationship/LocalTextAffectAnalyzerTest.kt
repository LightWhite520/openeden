package io.openeden.relationship

import io.openeden.memory.LocalTextEmbeddingSpec
import io.openeden.nn.LocalActivation
import io.openeden.nn.LocalDenseLayerSpec
import io.openeden.nn.LocalMlpSpec
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalTextAffectAnalyzerTest {
    @Test
    fun `local classifier returns six bounded affect values`() = runTest {
        val analyzer = LocalTextAffectAnalyzer(
            textEmbeddingSpec = LocalTextEmbeddingSpec(
                bucketSize = 4,
                projector = LocalMlpSpec(
                    inputSize = 4,
                    layers = listOf(identityLayer(4)),
                ),
            ),
            affectSpec = LocalMlpSpec(
                inputSize = 4,
                layers = listOf(
                    LocalDenseLayerSpec(
                        outputSize = 6,
                        weights = List(6) { List(4) { 0.0f } },
                        biases = List(6) { index -> if (index == 5) 2.0f else 0.5f },
                        activation = LocalActivation.SIGMOID,
                    ),
                ),
            ),
        )

        val result = analyzer.analyze("hello")

        assertEquals(0.622459f, result.valence, 0.0001f)
        assertTrue(result.confidence > 0.8f)
        assertTrue(listOf(result.valence, result.arousal, result.dominance, result.connectionNeed, result.openness, result.confidence).all { it in 0.0f..1.0f })
    }

    @Test
    fun `invalid local classifier falls back`() = runTest {
        val analyzer = LocalTextAffectAnalyzer(
            textEmbeddingSpec = LocalTextEmbeddingSpec(
                bucketSize = 4,
                projector = LocalMlpSpec(inputSize = 4, layers = listOf(identityLayer(3))),
            ),
            affectSpec = LocalMlpSpec(
                inputSize = 4,
                layers = listOf(
                    LocalDenseLayerSpec(
                        outputSize = 6,
                        weights = List(6) { List(4) { 0.0f } },
                        biases = List(6) { 0.0f },
                    ),
                ),
            ),
        )

        assertEquals(0.35f, analyzer.analyze("unsupported").confidence)
    }

    private fun identityLayer(size: Int): LocalDenseLayerSpec = LocalDenseLayerSpec(
        outputSize = size,
        weights = List(size) { row -> List(size) { column -> if (row == column) 1.0f else 0.0f } },
        biases = List(size) { 0.0f },
        activation = LocalActivation.LINEAR,
    )
}
