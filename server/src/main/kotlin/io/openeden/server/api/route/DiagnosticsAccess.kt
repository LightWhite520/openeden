package io.openeden.server.api.route

import io.ktor.util.AttributeKey
import java.security.MessageDigest

class DiagnosticsAccess private constructor(
    val enabled: Boolean,
    private val tokenBytes: ByteArray?,
) {
    fun authorizes(bearerToken: String?): Boolean =
        enabled &&
            tokenBytes != null &&
            bearerToken != null &&
            MessageDigest.isEqual(tokenBytes, bearerToken.encodeToByteArray())

    companion object {
        fun disabled() = DiagnosticsAccess(false, null)

        fun enabled(token: String): DiagnosticsAccess {
            require(token.isNotBlank()) { "Diagnostics token is required when diagnostics are enabled" }
            return DiagnosticsAccess(true, token.encodeToByteArray())
        }
    }
}

val DiagnosticsAccessKey = AttributeKey<DiagnosticsAccess>("OpenEdenDiagnosticsAccess")
