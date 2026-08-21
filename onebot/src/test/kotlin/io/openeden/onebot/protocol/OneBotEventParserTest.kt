package io.openeden.onebot.protocol

import io.openeden.onebot.config.OneBotConfig
import io.openeden.onebot.config.OneBotGroupPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OneBotEventParserTest {
    private val parser = OneBotEventParser()
    private val config = OneBotConfig(
        enabled = true,
        accessToken = "secret",
        botSelfId = "10001",
    )

    @Test
    fun `parses private group and action frames`() {
        val private = parser.parse(
            """{"self_id":10001,"post_type":"message","message_type":"private","message_id":7,"user_id":22,"message":"hello"}""",
            config,
        ) as OneBotInbound.Message
        assertEquals("hello", private.event.text)
        assertEquals(OneBotReplyTarget.Private("22"), private.event.target)

        val group = parser.parse(
            """{"self_id":10001,"post_type":"message","message_type":"group","message_id":8,"group_id":33,"user_id":22,"message":[{"type":"at","data":{"qq":"10001"}},{"type":"text","data":{"text":" hello"}}]}""",
            config,
        ) as OneBotInbound.Message
        assertEquals("hello", group.event.text)
        assertEquals(OneBotReplyTarget.Group("33"), group.event.target)

        assertIs<OneBotInbound.Ignored>(parser.parse("not-json", config))
        assertIs<OneBotInbound.Ignored>(parser.parse(
            """{"self_id":10001,"post_type":"message","message_type":"private","message_id":9,"user_id":10001,"message":"echo"}""",
            config,
        ))
        assertIs<OneBotInbound.Action>(parser.parse("""{"status":"ok","retcode":0,"echo":"e1"}""", config))
    }

    @Test
    fun `applies group mention policy`() {
        val unmentioned = """{"self_id":10001,"post_type":"message","message_type":"group","message_id":1,"group_id":33,"user_id":22,"message":"hello"}"""
        val mentioned = """{"self_id":10001,"post_type":"message","message_type":"group","message_id":2,"group_id":33,"user_id":22,"message":[{"type":"at","data":{"qq":"10001"}},{"type":"text","data":{"text":" hello"}}]}"""

        assertIs<OneBotInbound.Ignored>(parser.parse(unmentioned, config))
        assertIs<OneBotInbound.Message>(parser.parse(mentioned, config))
        assertIs<OneBotInbound.Message>(parser.parse(
            unmentioned,
            config.copy(groupPolicy = OneBotGroupPolicy.ALL),
        ))
        assertIs<OneBotInbound.Ignored>(parser.parse(
            mentioned,
            config.copy(groupPolicy = OneBotGroupPolicy.DISABLED),
        ))
    }
}
