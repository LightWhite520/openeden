package io.openeden.server.api.route

import io.ktor.util.AttributeKey
import java.security.MessageDigest

class MaintenanceAccess private constructor(
    val enabled: Boolean,
    private val token: ByteArray,
) {
    fun authorizes(candidate: String?): Boolean = enabled && candidate != null &&
        MessageDigest.isEqual(token, candidate.encodeToByteArray())

    companion object {
        fun disabled() = MaintenanceAccess(false, byteArrayOf())

        fun enabled(token: String): MaintenanceAccess {
            require(token.isNotBlank()) { "Enabled maintenance requires a non-blank bearer token" }
            return MaintenanceAccess(true, token.encodeToByteArray())
        }
    }
}

val MaintenanceAccessKey = AttributeKey<MaintenanceAccess>("openeden.maintenance-access")
