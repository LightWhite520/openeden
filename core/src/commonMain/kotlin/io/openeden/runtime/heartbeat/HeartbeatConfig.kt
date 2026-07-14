package io.openeden.runtime.heartbeat

data class HeartbeatConfig(
    val baseSilenceGateMs: Long = 5 * 60_000L,
    val shockSilenceGateMs: Long = 30 * 60_000L,
    val shockIntensityGate: Float = 0.7f,
)
