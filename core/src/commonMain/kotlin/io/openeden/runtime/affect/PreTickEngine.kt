package io.openeden.runtime.affect

import io.openeden.runtime.state.MAX_PRETICK_DELTA
import io.openeden.runtime.state.PRETICK_SKIP_CONFIDENCE

import io.openeden.bio.BioVector
import io.openeden.bio.VectorDelta

object PreTickEngine {
    fun apply(original: BioVector, signal: EmotionSignal): PreTickResult {
        if (signal.confidence < PRETICK_SKIP_CONFIDENCE) {
            return PreTickResult(
                original = original,
                preTicked = original,
                appliedDelta = VectorDelta.Zero,
                skipped = true,
            )
        }
        val scaled = signal.delta
            .scale(signal.confidence.coerceIn(0.0f, 1.0f))
            .clampMagnitude(MAX_PRETICK_DELTA)
        return PreTickResult(
            original = original,
            preTicked = original.apply(scaled),
            appliedDelta = scaled,
            skipped = false,
        )
    }
}
