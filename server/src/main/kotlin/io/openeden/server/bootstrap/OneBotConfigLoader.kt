package io.openeden.server.bootstrap

import io.ktor.server.config.ApplicationConfig
import io.openeden.onebot.config.OneBotConfig
import io.openeden.onebot.config.OneBotGroupPolicy
import io.openeden.runtime.heartbeat.HeartbeatOwner

internal fun loadOneBotConfig(config: ApplicationConfig): OneBotConfig {
    fun value(path: String, default: String): String =
        config.propertyOrNull(path)?.getString()?.trim() ?: default

    fun parseBoolean(path: String, default: Boolean): Boolean =
        when (value(path, default.toString()).lowercase()) {
            "true" -> true
            "false" -> false
            else -> throw IllegalArgumentException("$path must be true or false")
        }

    fun parseInt(path: String, default: Int): Int =
        value(path, default.toString()).toIntOrNull()
            ?: throw IllegalArgumentException("$path must be an integer")

    val groupPolicy = value(
        "openeden.onebot.groupPolicy",
        OneBotGroupPolicy.MENTION_ONLY.name,
    ).uppercase().let { raw ->
        runCatching { OneBotGroupPolicy.valueOf(raw) }.getOrElse {
            throw IllegalArgumentException("openeden.onebot.groupPolicy must be a valid OneBot group policy")
        }
    }

    return OneBotConfig(
        enabled = parseBoolean("openeden.onebot.enabled", false),
        path = value("openeden.onebot.path", "/onebot/v11"),
        accessToken = value("openeden.onebot.accessToken", ""),
        botSelfId = value("openeden.onebot.botSelfId", ""),
        groupPolicy = groupPolicy,
        eventQueueCapacity = parseInt("openeden.onebot.eventQueueCapacity", 64),
        eventWorkers = parseInt("openeden.onebot.eventWorkers", 4),
        actionTimeoutMs = parseInt("openeden.onebot.actionTimeoutMs", 10_000).toLong(),
        maxActionRetries = parseInt("openeden.onebot.maxActionRetries", 2),
    )
}

internal fun loadHeartbeatOwner(config: ApplicationConfig): HeartbeatOwner? {
    val platform = config.propertyOrNull("openeden.heartbeat.ownerPlatform")?.getString()
        ?.trim()?.takeIf(String::isNotBlank)
    val userId = config.propertyOrNull("openeden.heartbeat.ownerUserId")?.getString()
        ?.trim()?.takeIf(String::isNotBlank)
    require((platform == null) == (userId == null)) {
        "openeden.heartbeat.ownerPlatform and ownerUserId must be configured together"
    }
    return if (platform == null) null else HeartbeatOwner(platform, userId!!)
}

internal fun validateOneBotHeartbeatOwner(oneBotEnabled: Boolean, owner: HeartbeatOwner?) {
    require(!oneBotEnabled || owner == null || owner.platform == "QQ") {
        "OneBot heartbeat owner platform must be QQ"
    }
}
