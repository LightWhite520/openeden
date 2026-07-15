package io.openeden.codebook


import io.openeden.bio.BioVector
import io.openeden.nn.LocalActivation
import io.openeden.nn.LocalDenseLayerSpec
import io.openeden.nn.LocalMlpSpec
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalVqVaeCodebookRunnerTest {
    @Test
    fun `local vq runner encodes vector and selects nearest codebook nodes`() = runTest {
        val runner = LocalVqVaeCodebookModelRunner(
            LocalVqVaeSpec(
                encoder = LocalMlpSpec(
                    inputSize = 8,
                    layers = listOf(
                        LocalDenseLayerSpec(
                            outputSize = 2,
                            weights = listOf(
                                listOf(1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f),
                                listOf(0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f),
                            ),
                            biases = listOf(0.0f, 0.0f),
                            activation = LocalActivation.LINEAR,
                        ),
                    ),
                ),
                codebook = listOf(
                    CodebookVector("NODE_A", listOf(1.0f, 0.0f)),
                    CodebookVector("NODE_B", listOf(0.0f, 1.0f)),
                ),
                topK = 1,
            ),
        )

        val result = runner.predict(BioVector.Neutral.copy(l = 1.0f, p = 0.0f), dissonance = 0.0f)

        assertEquals(listOf("NODE_A"), result.nodeIds)
        assertTrue(result.confidence > 0.8f)
    }
}
