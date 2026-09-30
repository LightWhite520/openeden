package io.openeden.server.auth

import kotlinx.serialization.Serializable

@Serializable
data class ChatGptAccount(
    val clientId: String,
    val subject: String,
    val email: String?,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val idToken: String? = null,
    val expiresAtMs: Long = 0,
    val scopes: Set<String> = emptySet(),
    val pendingRefresh: PendingChatGptRefresh? = null,
) {
    override fun toString(): String = "ChatGptAccount(clientId=$clientId, signedIn=${accessToken != null})"
}
