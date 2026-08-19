package io.openeden.runtime.affect

import kotlin.time.Instant

/** A free-text shock event before it is merged with persisted session state. */
data class ShockSignal(
    val description: String,
    val intensity: Float,
    val decayLambda: Float,
    val triggeredAt: Instant,
) {
    init {
        require(intensity.isFinite()) { "Shock intensity must be finite" }
        require(decayLambda.isFinite() && decayLambda >= 0.0f) {
            "Shock decay lambda must be finite and non-negative"
        }
    }
}
