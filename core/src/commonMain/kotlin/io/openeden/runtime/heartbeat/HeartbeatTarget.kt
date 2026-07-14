package io.openeden.runtime.heartbeat

data class HeartbeatTarget(
    val platform: String,
    val userId: String,
)

data class HeartbeatOwner(
    val platform: String,
    val userId: String,
)
