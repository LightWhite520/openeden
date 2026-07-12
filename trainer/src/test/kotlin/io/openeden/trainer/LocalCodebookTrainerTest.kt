package io.openeden.trainer

import io.openeden.bio.BioVector
import io.openeden.codebook.VqVaeCodebookQuantizer
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalCodebookTrainerTest {
    @Test
    fun `trainer produces artifact that quantizes samples to csv definitions`() = runTest {
        val corpus = CodebookTrainingCorpus(
            samples = listOf(
                sample("NODE_A", "logical state", BioVector.Neutral.copy(l = 0.9f, p = 0.1f)),
                sample("NODE_B", "emotional state", BioVector.Neutral.copy(l = 0.1f, p = 0.9f)),
            ),
        )
        val trainer = LocalCodebookTrainer()
        val artifact = trainer.train(corpus)
        val quantizer: VqVaeCodebookQuantizer = artifact.codebookQuantizer(minConfidence = 0.0f)

        val result = quantizer.quantize(BioVector.Neutral.copy(l = 0.9f, p = 0.1f), dissonance = 0.0f)

        assertEquals("NODE_A", result.activeNodes.first())
        assertTrue("logical state" in result.semanticDefinitions)
        assertEquals(1.0f, trainer.evaluate(corpus, artifact))
    }

    @Test
    fun `training samples do not carry derived dissonance`() {
        val descriptor = CodebookTrainingSample.serializer().descriptor
        val fields = List(descriptor.elementsCount) { index -> descriptor.getElementName(index) }

        assertEquals(listOf("nodeId", "definition", "definitionEn", "definitionZh", "tags", "vector"), fields)
    }

    @Test
    fun `trainer preserves bilingual codebook definitions`() {
        val corpus = CodebookTrainingCorpus(
            samples = listOf(
                CodebookTrainingSample(
                    nodeId = "NODE_A",
                    definition = "logical state",
                    definitionEn = "logical state",
                    definitionZh = "逻辑状态",
                    tags = listOf("test"),
                    vector = BioVector.Neutral.copy(l = 0.9f, p = 0.1f),
                ),
            ),
        )

        val artifact = LocalCodebookTrainer().train(corpus)

        assertTrue("definition_en" in artifact.codebookCsv)
        assertTrue("definition_zh" in artifact.codebookCsv)
        assertTrue("逻辑状态" in artifact.codebookCsv)
    }

    private fun sample(nodeId: String, definition: String, vector: BioVector): CodebookTrainingSample =
        CodebookTrainingSample(
            nodeId = nodeId,
            definition = definition,
            tags = listOf("test"),
            vector = vector,
        )
}
