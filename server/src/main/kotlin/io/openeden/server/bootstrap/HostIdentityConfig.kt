package io.openeden.server.bootstrap

import io.ktor.server.config.ApplicationConfig
import io.openeden.relationship.HostIdentity

internal fun loadHostIdentity(config: ApplicationConfig): HostIdentity? {
    val platform = config.propertyOrNull("openeden.relationship.hostPlatform")
        ?.getString()
        ?.takeIf { it.isNotBlank() }
    val userId = config.propertyOrNull("openeden.relationship.hostUserId")
        ?.getString()
        ?.takeIf { it.isNotBlank() }

    require((platform == null) == (userId == null)) {
        "Host platform and user ID must be configured together"
    }
    return if (platform != null && userId != null) HostIdentity(platform, userId) else null
}
