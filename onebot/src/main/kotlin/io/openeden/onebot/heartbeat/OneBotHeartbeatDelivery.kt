package io.openeden.onebot.heartbeat

import io.openeden.onebot.connection.OneBotConnectionRegistry
import io.openeden.onebot.egress.OneBotActionException
import io.openeden.onebot.egress.OneBotActionSender
import io.openeden.runtime.heartbeat.HeartbeatDelivery
import io.openeden.runtime.heartbeat.HeartbeatTarget

class OneBotHeartbeatDelivery(
    private val registry: OneBotConnectionRegistry,
    private val actions: OneBotActionSender,
) : HeartbeatDelivery {
    override fun isConnected(target: HeartbeatTarget): Boolean =
        target.platform == QQ_PLATFORM && registry.snapshot() != null

    override suspend fun deliver(
        sessionId: String,
        target: HeartbeatTarget,
        shock: Boolean,
        response: String?,
    ) {
        val text = response?.takeIf(String::isNotBlank) ?: return
        if (target.platform != QQ_PLATFORM) throw OneBotActionException(
            OneBotActionException.Category.REJECTED,
            "OneBot heartbeat target must use QQ platform",
        )
        val epoch = registry.snapshot()?.epoch ?: throw OneBotActionException(
            OneBotActionException.Category.DISCONNECTED,
            "OneBot is disconnected",
        )
        actions.sendPrivate(target.userId, text, requiredEpoch = epoch)
    }

    private companion object { const val QQ_PLATFORM = "QQ" }
}
