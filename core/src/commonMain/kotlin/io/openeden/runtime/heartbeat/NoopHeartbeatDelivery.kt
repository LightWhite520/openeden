package io.openeden.runtime.heartbeat

object NoopHeartbeatDelivery : HeartbeatDelivery {
    override fun isConnected(target: HeartbeatTarget): Boolean = false

    override suspend fun deliver(sessionId: String, target: HeartbeatTarget, shock: Boolean, response: String?) {}
}
