package io.openeden.trainer

import io.openeden.codebook.CodebookVector
import io.openeden.codebook.LocalVqVaeCodebookModelRunner
import io.openeden.codebook.LocalVqVaeSpec
import io.openeden.memory.LocalTextEmbeddingSpec
import io.openeden.model.LocalModelArtifact
import io.openeden.nn.LocalActivation
import io.openeden.nn.LocalDenseLayerSpec
import io.openeden.nn.LocalMlpSpec
import io.openeden.nn.l2Normalize

class LocalCodebookTrainer(
    private val config: TrainingConfig = TrainingConfig(),
) {
    fun train(corpus: CodebookTrainingCorpus): LocalModelArtifact {
        require(corpus.samples.isNotEmpty()) { "Training corpus must contain at least one sample" }
        val definitions = validateAndDefinitions(corpus)
        val codebook = corpus.samples
            .groupBy { it.nodeId }
            .map { (nodeId, samples) ->
                CodebookVector(
                    nodeId = nodeId,
                    embedding = averageVectors(samples.map { it.vector.toList().take(config.latentDimensions) }),
                )
            }
            .sortedBy { it.nodeId }
        val vqVae = LocalVqVaeSpec(
            encoder = encoderSpec(),
            codebook = codebook,
            topK = config.topK,
        )
        return LocalModelArtifact(
            codebookCsv = codebookCsv(definitions),
            vqVae = vqVae,
            textEmbedding = textEmbeddingSpec(),
            emotionalEmbedding = emotionalEmbeddingSpec(),
            textAffect = textAffectSpec(),
        )
    }

    suspend fun evaluate(corpus: CodebookTrainingCorpus, artifact: LocalModelArtifact): Float {
        val runner = LocalVqVaeCodebookModelRunner(artifact.vqVae)
        var correct = 0
        for (sample in corpus.samples) {
            val result = runner.predict(sample.vector, dissonance = 0.0f)
            if (result.nodeIds.firstOrNull() == sample.nodeId) {
                correct += 1
            }
        }
        return correct.toFloat() / corpus.samples.size
    }

    private fun validateAndDefinitions(corpus: CodebookTrainingCorpus): Map<String, CodebookTrainingSample> {
        val definitions = linkedMapOf<String, CodebookTrainingSample>()
        for (sample in corpus.samples) {
            require(sample.nodeId.matches(Regex("NODE_[A-Za-z0-9_]+"))) {
                "nodeId must use NODE_* format: ${sample.nodeId}"
            }
            require(sample.definition.isNotBlank()) { "definition must not be blank for ${sample.nodeId}" }
            val existing = definitions[sample.nodeId]
            if (existing != null) {
                require(existing.definitionForCsv() == sample.definitionForCsv()) {
                    "Conflicting definitions for ${sample.nodeId}"
                }
            } else {
                definitions[sample.nodeId] = sample
            }
        }
        return definitions
    }

    private fun encoderSpec(): LocalMlpSpec =
        LocalMlpSpec(
            inputSize = 9,
            layers = listOf(
                LocalDenseLayerSpec(
                    outputSize = config.latentDimensions,
                    weights = List(config.latentDimensions) { output ->
                        List(9) { input -> if (input == output) 1.0f else 0.0f }
                    },
                    biases = List(config.latentDimensions) { 0.0f },
                    activation = LocalActivation.LINEAR,
                ),
            ),
        )

    private fun textEmbeddingSpec(): LocalTextEmbeddingSpec =
        LocalTextEmbeddingSpec(
            bucketSize = config.textBucketSize,
            projector = LocalMlpSpec(
                inputSize = config.textBucketSize,
                layers = listOf(
                    LocalDenseLayerSpec(
                        outputSize = config.textEmbeddingDimensions,
                        weights = List(config.textEmbeddingDimensions) { output ->
                            List(config.textBucketSize) { input ->
                                if (input % config.textEmbeddingDimensions == output) 1.0f else 0.0f
                            }
                        },
                        biases = List(config.textEmbeddingDimensions) { 0.0f },
                        activation = LocalActivation.LINEAR,
                    ),
                ),
            ),
        )

    private fun emotionalEmbeddingSpec(): LocalMlpSpec =
        LocalMlpSpec(
            inputSize = 8,
            layers = listOf(
                LocalDenseLayerSpec(
                    outputSize = 8,
                    weights = List(8) { output -> List(8) { input -> if (input == output) 1.0f else 0.0f } },
                    biases = List(8) { 0.0f },
                    activation = LocalActivation.LINEAR,
                ),
            ),
        )

    private fun textAffectSpec(): LocalMlpSpec =
        LocalMlpSpec(
            inputSize = config.textEmbeddingDimensions,
            layers = listOf(
                LocalDenseLayerSpec(
                    outputSize = 6,
                    weights = List(6) { output ->
                        List(config.textEmbeddingDimensions) { input ->
                            if (input % 6 == output) 0.25f else 0.0f
                        }
                    },
                    biases = List(6) { index -> if (index == 5) 0.4f else 0.0f },
                    activation = LocalActivation.SIGMOID,
                ),
            ),
        )

    private fun averageVectors(vectors: List<List<Float>>): List<Float> {
        val sums = FloatArray(config.latentDimensions)
        for (vector in vectors) {
            require(vector.size == config.latentDimensions) {
                "Expected ${config.latentDimensions} vector dimensions"
            }
            for (index in vector.indices) {
                sums[index] += vector[index]
            }
        }
        return l2Normalize(sums.map { it / vectors.size })
    }

    private fun codebookCsv(definitions: Map<String, CodebookTrainingSample>): String = buildString {
        appendLine("node_id,definition_en,definition_zh,tags")
        for ((nodeId, sample) in definitions) {
            append(nodeId.csv())
            append(',')
            append(sample.definitionForCsv().csv())
            append(',')
            append(sample.chineseDefinitionForCsv().csv())
            append(',')
            appendLine(sample.tags.joinToString(";").csv())
        }
    }

    private fun CodebookTrainingSample.definitionForCsv(): String =
        definitionEn?.takeIf { it.isNotBlank() } ?: definition

    private fun CodebookTrainingSample.chineseDefinitionForCsv(): String =
        definitionZh?.takeIf { it.isNotBlank() } ?: ""

    private fun String.csv(): String =
        "\"${replace("\"", "\"\"")}\""
}
