package io.openeden.server.auth

import kotlinx.serialization.Serializable

@Serializable
data class ChatGptAccounts(
    val hostId: String,
    val activeClientId: String? = null,
    val accounts: List<ChatGptAccount> = emptyList(),
) {
    override fun toString(): String = "ChatGptAccounts(accountCount=${accounts.size})"
}
