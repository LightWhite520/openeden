package io.openeden.runtime.heartbeat

interface HeartbeatDelivery {
    suspend fun deliver(sessionId: String, target: HeartbeatTarget, shock: Boolean, response: String?)
}

object NoopHeartbeatDelivery : HeartbeatDelivery {
    override suspend fun deliver(sessionId: String, target: HeartbeatTarget, shock: Boolean, response: String?) {}
}

class LoggingHeartbeatDelivery(private val sink: (String) -> Unit = ::println) : HeartbeatDelivery {
    override suspend fun deliver(sessionId: String, target: HeartbeatTarget, shock: Boolean, response: String?) {
        val kind = if (shock) "shock" else "base"
        sink("[heartbeat:$kind] $sessionId -> ${target.platform}:${target.userId} ${response ?: "<no response>"}")
    }
}
