package io.openeden.trainer

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
