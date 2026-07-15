package io.openeden.codebook

import io.openeden.bio.BioVector
import io.openeden.memory.DjlMemoryEmbeddingModel
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class DjlTorchScriptSmokeTest {
    @Test
    fun `real DJL loads exported TorchScript predictors`() = runTest {
        val root = Path.of(
            System.getProperty("openeden.djl.smoke.dir", "../data/models/djl"),
        ).toAbsolutePath().normalize()
        val runner = DjlVqVaeCodebookModelRunner.fromModelPath(
            modelPath = root.resolve("vqvae"),
            modelName = "model",
            engineName = "PyTorch",
            inputDimension = 8,
            codebook = listOf(CodebookVector("NODE_A", listOf(1.0f) + List(7) { 0.0f })),
            topK = 1,
        )
        val result = runner.predict(BioVector.Neutral, 0.0f)
        assertEquals(listOf("NODE_A"), result.nodeIds)

        val embedding = DjlMemoryEmbeddingModel.fromModelPaths(
            textModelPath = root.resolve("text"),
            emotionalModelPath = root.resolve("emotional"),
            textModelName = "model",
            emotionalModelName = "model",
            engineName = "PyTorch",
            textInputDimension = 32,
        )
        assertTrue(embedding.embed("smoke").all(Float::isFinite))
        assertEquals(8, embedding.embed(BioVector.Neutral).size)
        embedding.close()
        runner.close()
    }
}
