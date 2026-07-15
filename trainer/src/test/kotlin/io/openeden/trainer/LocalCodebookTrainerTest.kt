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

        assertEquals(listOf("nodeId", "definition", "definitionEn", "definitionZh", "trainingTextZh", "tags", "vector"), fields)
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

    @Test
    fun `training text variants do not replace runtime codebook definitions`() {
        val canonicalZh = "说话者反复说一切都好，但动作越来越失常，像面具正在当场松脱。"
        val variantZh = "“放心，我还稳得住。”她一边说一边后退半步。"
        val corpus = CodebookTrainingCorpus(
            samples = listOf(
                CodebookTrainingSample(
                    nodeId = "NODE_BIG_NEGATION_CALM_BUT_PANIC_03",
                    definition = "calm mask slips into panic",
                    definitionEn = "The speaker says everything is fine while panic leaks through action.",
                    definitionZh = canonicalZh,
                    trainingTextZh = variantZh,
                    tags = listOf("hardcase"),
                    vector = BioVector.Neutral.copy(p = 0.76f, s = 0.83f, f = 0.79f),
                ),
            ),
        )

        val artifact = LocalCodebookTrainer().train(corpus)

        assertTrue(canonicalZh in artifact.codebookCsv)
        assertTrue(variantZh !in artifact.codebookCsv)
    }

    private fun sample(nodeId: String, definition: String, vector: BioVector): CodebookTrainingSample =
        CodebookTrainingSample(
            nodeId = nodeId,
            definition = definition,
            tags = listOf("test"),
            vector = vector,
        )
}
