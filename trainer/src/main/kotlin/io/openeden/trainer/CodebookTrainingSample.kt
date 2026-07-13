package io.openeden.trainer

import io.openeden.bio.BioVector
import kotlinx.serialization.Serializable

@Serializable
data class CodebookTrainingSample(
    val nodeId: String,
    val definition: String,
    val definitionEn: String? = null,
    val definitionZh: String? = null,
    val tags: List<String> = emptyList(),
    val vector: BioVector,
)
