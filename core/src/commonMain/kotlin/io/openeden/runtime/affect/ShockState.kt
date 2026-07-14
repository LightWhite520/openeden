package io.openeden.runtime.affect

import kotlin.time.Instant

data class ShockState(
    val active: Boolean,
    val intensity: Float,
    val description: String,
    val triggeredAt: Instant,
    val decayLambda: Float,
    val shockHeartbeatFired: Boolean = false,
)
