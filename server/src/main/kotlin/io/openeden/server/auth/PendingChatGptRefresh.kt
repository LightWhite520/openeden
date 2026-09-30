package io.openeden.server.auth

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Protected recovery checkpoint, never a usable bearer credential until validated. */
@Serializable
data class PendingChatGptRefresh(val tokens: JsonObject, val receivedAtMs: Long) {
    override fun toString(): String = "PendingChatGptRefresh(redacted)"
}
