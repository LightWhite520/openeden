package io.openeden.model


import io.openeden.bio.BioVector
import io.openeden.codebook.CodebookVector
import io.openeden.codebook.LocalVqVaeSpec
import io.openeden.memory.LocalTextEmbeddingSpec
import io.openeden.nn.LocalActivation
import io.openeden.nn.LocalDenseLayerSpec
import io.openeden.nn.LocalMlpSpec
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalModelArtifactLoaderTest {
    @Test
    fun `writes and reads local model artifact`() {
        val path = Files.createTempFile("openeden-local-model", ".json")
        val artifact = artifact()

        LocalModelArtifactLoader.write(path, artifact)
        val loaded = LocalModelArtifactLoader.read(path)
        val quantizer = loaded.codebookQuantizer(minConfidence = 0.0f)
        val result = runBlocking { quantizer.quantize(BioVector.Neutral, 0.0f) }
        val embedding = runBlocking { loaded.memoryEmbeddingModel().embed(BioVector.Neutral) }

        assertEquals(listOf("NODE_000"), result.activeNodes)
        assertTrue("neutral" in result.semanticDefinitions.single())
        assertEquals(8, embedding.size)
    }

    private fun artifact(): LocalModelArtifact =
        LocalModelArtifact(
            codebookCsv = "node_id,definition,tags\nNODE_000,\"neutral\",\"test\"\n",
            vqVae = LocalVqVaeSpec(
                encoder = mlp(input = 8, output = 8),
                codebook = listOf(CodebookVector("NODE_000", BioVector.Neutral.toList())),
                topK = 1,
            ),
            textEmbedding = LocalTextEmbeddingSpec(
                bucketSize = 8,
                projector = mlp(input = 8, output = 4),
            ),
            emotionalEmbedding = mlp(input = 8, output = 8),
        )

    private fun mlp(input: Int, output: Int): LocalMlpSpec =
        LocalMlpSpec(
            inputSize = input,
            layers = listOf(
                LocalDenseLayerSpec(
                    outputSize = output,
                    weights = List(output) { row -> List(input) { column -> if (row == column) 1.0f else 0.0f } },
                    biases = List(output) { 0.0f },
                    activation = LocalActivation.LINEAR,
                ),
            ),
        )
}
