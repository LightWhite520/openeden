package io.openeden.runtime.heartbeat

class LoggingHeartbeatDelivery(private val sink: (String) -> Unit = ::println) : HeartbeatDelivery {
    override fun isConnected(target: HeartbeatTarget): Boolean = true

    override suspend fun deliver(sessionId: String, target: HeartbeatTarget, shock: Boolean, response: String?) {
        val kind = if (shock) "shock" else "base"
        sink("[heartbeat:$kind] $sessionId -> ${target.platform}:${target.userId} ${response ?: "<no response>"}")
    }
}
