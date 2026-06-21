package io.openeden.nn

import kotlin.test.Test
import kotlin.test.assertEquals

class LocalNeuralNetworkTest {
    @Test
    fun `mlp runs dense layers locally`() {
        val mlp = LocalMlp(
            LocalMlpSpec(
                inputSize = 2,
                layers = listOf(
                    LocalDenseLayerSpec(
                        outputSize = 2,
                        weights = listOf(
                            listOf(1.0f, 0.0f),
                            listOf(0.0f, 1.0f),
                        ),
                        biases = listOf(0.5f, -0.5f),
                        activation = LocalActivation.RELU,
                    ),
                    LocalDenseLayerSpec(
                        outputSize = 1,
                        weights = listOf(listOf(1.0f, 1.0f)),
                        biases = listOf(0.0f),
                    ),
                ),
            ),
        )

        assertEquals(listOf(1.5f), mlp.forward(listOf(1.0f, 0.0f)))
    }
}
