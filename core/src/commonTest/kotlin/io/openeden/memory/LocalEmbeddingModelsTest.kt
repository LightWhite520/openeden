package io.openeden.memory

import io.openeden.bio.BioVector
import io.openeden.nn.LocalActivation
import io.openeden.nn.LocalDenseLayerSpec
import io.openeden.nn.LocalMlpSpec
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalEmbeddingModelsTest {
    @Test
    fun `local neural text embedding projects hashed buckets`() = runTest {
        val model = LocalNeuralTextEmbeddingModel(
            LocalTextEmbeddingSpec(
                bucketSize = 4,
                projector = LocalMlpSpec(
                    inputSize = 4,
                    layers = listOf(
                        LocalDenseLayerSpec(
                            outputSize = 2,
                            weights = listOf(
                                listOf(1.0f, 0.0f, 0.0f, 0.0f),
                                listOf(0.0f, 1.0f, 0.0f, 0.0f),
                            ),
                            biases = listOf(0.0f, 0.0f),
                            activation = LocalActivation.LINEAR,
                        ),
                    ),
                ),
            ),
        )

        assertEquals(2, model.embed("hello").size)
    }

    @Test
    fun `local neural emotional embedding projects 8d vector`() = runTest {
        val model = LocalNeuralEmotionalEmbeddingModel(
            LocalMlpSpec(
                inputSize = 8,
                layers = listOf(
                    LocalDenseLayerSpec(
                        outputSize = 3,
                        weights = listOf(
                            listOf(1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f),
                            listOf(0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f),
                            listOf(0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f),
                        ),
                        biases = listOf(0.0f, 0.0f, 0.0f),
                        activation = LocalActivation.LINEAR,
                    ),
                ),
            ),
        )

        assertEquals(3, model.embed(BioVector.Neutral).size)
    }
}
