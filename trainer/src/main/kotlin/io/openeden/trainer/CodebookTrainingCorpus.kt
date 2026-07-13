package io.openeden.trainer

import kotlinx.serialization.Serializable

@Serializable
data class CodebookTrainingCorpus(
    val samples: List<CodebookTrainingSample>,
)
