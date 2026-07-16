package io.openeden.server.bootstrap

import io.ktor.server.config.MapApplicationConfig
import io.openeden.relationship.HostIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class HostIdentityConfigTest {
    @Test
    fun `missing host coordinates leave host identity unconfigured`() {
        assertNull(loadHostIdentity(MapApplicationConfig()))
    }

    @Test
    fun `complete host coordinates load an exact identity`() {
        val config = MapApplicationConfig(
            "openeden.relationship.hostPlatform" to "QQ",
            "openeden.relationship.hostUserId" to "owner",
        )

        assertEquals(HostIdentity("QQ", "owner"), loadHostIdentity(config))
    }

    @Test
    fun `optional host address loads only with complete identity`() {
        val config = MapApplicationConfig(
            "openeden.relationship.hostPlatform" to "QQ",
            "openeden.relationship.hostUserId" to "owner",
            "openeden.relationship.hostAddress" to "林先生",
        )
        val identity = loadHostIdentity(config)

        assertEquals("林先生", loadHostAddress(config, identity))
        assertNull(
            loadHostAddress(
                MapApplicationConfig(
                    "openeden.relationship.hostPlatform" to "QQ",
                    "openeden.relationship.hostUserId" to "owner",
                ),
                HostIdentity("QQ", "owner"),
            ),
        )
        assertNull(loadHostAddress(MapApplicationConfig(), hostIdentity = null))
    }

    @Test
    fun `host address without host identity is rejected`() {
        val config = MapApplicationConfig("openeden.relationship.hostAddress" to "主人")

        assertFailsWith<IllegalArgumentException> {
            loadHostAddress(config, loadHostIdentity(config))
        }
    }

    @Test
    fun `partial host configuration is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            loadHostIdentity(
                MapApplicationConfig("openeden.relationship.hostPlatform" to "QQ"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            loadHostIdentity(
                MapApplicationConfig("openeden.relationship.hostUserId" to "owner"),
            )
        }
    }
}
