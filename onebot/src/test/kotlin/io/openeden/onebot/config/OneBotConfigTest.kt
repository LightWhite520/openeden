package io.openeden.onebot.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OneBotConfigTest {
    @Test
    fun `enabled config requires token self id and absolute path`() {
        assertFailsWith<IllegalArgumentException> {
            OneBotConfig(enabled = true, accessToken = "", botSelfId = "10001")
        }
        assertFailsWith<IllegalArgumentException> {
            OneBotConfig(enabled = true, accessToken = "secret", botSelfId = "")
        }
        assertFailsWith<IllegalArgumentException> {
            OneBotConfig(enabled = true, path = "onebot", accessToken = "secret", botSelfId = "10001")
        }
    }

    @Test
    fun `defaults are bounded and mention only`() {
        val config = OneBotConfig(enabled = false)
        assertEquals(OneBotGroupPolicy.MENTION_ONLY, config.groupPolicy)
        assertEquals(64, config.eventQueueCapacity)
        assertEquals(4, config.eventWorkers)
        assertEquals(10_000L, config.actionTimeoutMs)
        assertEquals(2, config.maxActionRetries)
    }
}
