package io.openeden.codebook

import kotlinx.serialization.Serializable

@Serializable
data class CodebookVector(
    val nodeId: String,
    val embedding: List<Float>,
)
