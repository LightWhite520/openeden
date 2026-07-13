package io.openeden.trainer

data class TrainingConfig(
    val latentDimensions: Int = 8,
    val textBucketSize: Int = 32,
    val textEmbeddingDimensions: Int = 16,
    val topK: Int = 3,
)
