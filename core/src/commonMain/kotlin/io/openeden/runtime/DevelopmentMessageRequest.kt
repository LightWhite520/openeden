package io.openeden.runtime

import io.openeden.bio.VectorDelta

data class DevelopmentMessageRequest(
    val platform: String,
    val scopeId: String,
    val userId: String,
    val text: String,
    val emotionConfidence: Float = 0.0f,
    val emotionDelta: VectorDelta = VectorDelta.Zero,
    val source: TurnSource = TurnSource.USER,
)
