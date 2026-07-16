package io.openeden.runtime.pipeline

import io.openeden.bio.VectorDelta

data class DevelopmentMessageRequest(
    val turnId: String,
    val platform: String,
    val scopeId: String,
    val userId: String,
    val text: String,
    val emotionConfidence: Float = 0.0f,
    val emotionDelta: VectorDelta = VectorDelta.Zero,
    val source: TurnSource = TurnSource.USER,
)
