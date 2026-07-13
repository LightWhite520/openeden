package io.openeden.runtime

import io.openeden.bio.VectorDelta

data class EmotionSignal(
    val delta: VectorDelta,
    val confidence: Float,
)
