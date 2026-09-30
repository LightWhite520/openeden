package io.openeden.server.auth

class ChatGptLoginAttempt internal constructor(
    val authorizationUrl: String,
    internal val state: String,
    internal val nonce: String,
    internal val verifier: String,
    internal val redirectUri: String,
    internal val hostId: String,
    internal val account: ChatGptAccount?,
) {
    override fun toString(): String = "ChatGptLoginAttempt(pending)"
}
