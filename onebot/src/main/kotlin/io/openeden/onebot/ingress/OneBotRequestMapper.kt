package io.openeden.onebot.ingress

import io.openeden.onebot.protocol.OneBotMessageEvent
import io.openeden.onebot.protocol.OneBotReplyTarget
import io.openeden.runtime.pipeline.DevelopmentMessageRequest

object OneBotRequestMapper {
    fun map(event: OneBotMessageEvent): DevelopmentMessageRequest = DevelopmentMessageRequest(
        turnId = "onebot_${event.selfId}_${event.messageId}",
        platform = "QQ",
        scopeId = when (val target = event.target) {
            is OneBotReplyTarget.Private -> target.userId
            is OneBotReplyTarget.Group -> target.groupId
        },
        userId = event.userId,
        text = event.text,
    )
}
