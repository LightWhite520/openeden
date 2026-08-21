package io.openeden.onebot.ingress

import io.openeden.onebot.protocol.OneBotMessageEvent
import io.openeden.onebot.protocol.OneBotReplyTarget
import kotlin.test.Test
import kotlin.test.assertEquals

class OneBotRequestMapperTest {
    @Test
    fun `maps private and group events to shared runtime request`() {
        val privateRequest = OneBotRequestMapper.map(
            OneBotMessageEvent(
                selfId = "10001",
                messageId = "7",
                userId = "22",
                text = "hello",
                target = OneBotReplyTarget.Private("22"),
            ),
        )
        assertEquals("QQ", privateRequest.platform)
        assertEquals("22", privateRequest.scopeId)
        assertEquals("22", privateRequest.userId)
        assertEquals("onebot_10001_7", privateRequest.turnId)

        val groupRequest = OneBotRequestMapper.map(
            OneBotMessageEvent(
                selfId = "10001",
                messageId = "8",
                userId = "22",
                text = "hello",
                target = OneBotReplyTarget.Group("33"),
            ),
        )
        assertEquals("33", groupRequest.scopeId)
        assertEquals("22", groupRequest.userId)
    }
}
