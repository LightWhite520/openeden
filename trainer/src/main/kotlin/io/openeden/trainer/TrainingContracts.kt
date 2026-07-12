package io.openeden.trainer

import io.openeden.bio.BioVector
import kotlinx.serialization.Serializable

@Serializable
data class CodebookTrainingCorpus(
    val samples: List<CodebookTrainingSample>,
)

@Serializable
data class CodebookTrainingSample(
    val nodeId: String,
    val definition: String,
    val definitionEn: String? = null,
    val definitionZh: String? = null,
    val tags: List<String> = emptyList(),
    val vector: BioVector,
)

data class TrainingConfig(
    val latentDimensions: Int = 8,
    val textBucketSize: Int = 32,
    val textEmbeddingDimensions: Int = 16,
    val topK: Int = 3,
)

data class TrainingReport(
    val sampleCount: Int,
    val nodeCount: Int,
    val top1Accuracy: Float,
    val artifactPath: String,
    val codebookCsvPath: String,
) {
    fun render(): String = buildString {
        appendLine("OpenEden local model training report")
        appendLine("samples=$sampleCount")
        appendLine("nodes=$nodeCount")
        appendLine("top1_accuracy=${top1Accuracy.format4()}")
        appendLine("artifact=$artifactPath")
        appendLine("codebook_csv=$codebookCsvPath")
    }
}

private fun Float.format4(): String =
    ((this * 10000.0f).toInt() / 10000.0f).toString()
