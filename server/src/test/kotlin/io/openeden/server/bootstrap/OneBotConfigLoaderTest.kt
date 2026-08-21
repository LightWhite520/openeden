package io.openeden.server.bootstrap

import io.ktor.server.config.MapApplicationConfig
import io.openeden.onebot.config.OneBotGroupPolicy
import io.openeden.runtime.heartbeat.HeartbeatOwner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OneBotConfigLoaderTest {
    @Test
    fun `missing onebot settings disable the adapter`() {
        val config = loadOneBotConfig(MapApplicationConfig())

        assertEquals(false, config.enabled)
        assertEquals("/onebot/v11", config.path)
        assertEquals(OneBotGroupPolicy.MENTION_ONLY, config.groupPolicy)
    }

    @Test
    fun `configured onebot settings are loaded`() {
        val config = loadOneBotConfig(
            MapApplicationConfig(
                "openeden.onebot.enabled" to "true",
                "openeden.onebot.path" to "/qq/ws",
                "openeden.onebot.accessToken" to " secret ",
                "openeden.onebot.botSelfId" to "10001",
                "openeden.onebot.groupPolicy" to "all",
                "openeden.onebot.eventQueueCapacity" to "128",
                "openeden.onebot.eventWorkers" to "2",
                "openeden.onebot.actionTimeoutMs" to "5000",
                "openeden.onebot.maxActionRetries" to "1",
            ),
        )

        assertEquals(true, config.enabled)
        assertEquals("/qq/ws", config.path)
        assertEquals("secret", config.accessToken)
        assertEquals("10001", config.botSelfId)
        assertEquals(OneBotGroupPolicy.ALL, config.groupPolicy)
        assertEquals(128, config.eventQueueCapacity)
        assertEquals(2, config.eventWorkers)
        assertEquals(5_000L, config.actionTimeoutMs)
        assertEquals(1, config.maxActionRetries)
    }

    @Test
    fun `enabled onebot requires token and self id`() {
        assertFailsWith<IllegalArgumentException> {
            loadOneBotConfig(
                MapApplicationConfig("openeden.onebot.enabled" to "true"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            loadOneBotConfig(
                MapApplicationConfig(
                    "openeden.onebot.enabled" to "true",
                    "openeden.onebot.accessToken" to "secret",
                ),
            )
        }
    }

    @Test
    fun `invalid onebot values are rejected`() {
        listOf(
            "openeden.onebot.enabled" to "maybe",
            "openeden.onebot.groupPolicy" to "unknown",
            "openeden.onebot.eventQueueCapacity" to "0",
            "openeden.onebot.eventWorkers" to "65",
            "openeden.onebot.actionTimeoutMs" to "99",
            "openeden.onebot.maxActionRetries" to "6",
        ).forEach { (path, value) ->
            assertFailsWith<IllegalArgumentException> {
                loadOneBotConfig(MapApplicationConfig(path to value))
            }
        }
    }

    @Test
    fun `heartbeat owner coordinates must be complete`() {
        assertFailsWith<IllegalArgumentException> {
            loadHeartbeatOwner(MapApplicationConfig("openeden.heartbeat.ownerPlatform" to "QQ"))
        }
        assertFailsWith<IllegalArgumentException> {
            loadHeartbeatOwner(MapApplicationConfig("openeden.heartbeat.ownerUserId" to "owner"))
        }
    }

    @Test
    fun `enabled onebot requires a QQ heartbeat owner`() {
        assertFailsWith<IllegalArgumentException> {
            validateOneBotHeartbeatOwner(true, HeartbeatOwner("CLI", "owner"))
        }
        validateOneBotHeartbeatOwner(false, HeartbeatOwner("CLI", "owner"))
    }
}
