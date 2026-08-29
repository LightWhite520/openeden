package io.openeden.prompt

import io.openeden.hash.Sha256

@JvmInline
value class ConversationCacheIdentity private constructor(val opaqueValue: String) {
    init {
        require(opaqueValue.matches(OPAQUE_PATTERN)) { "Conversation cache identity must be opaque" }
    }

    companion object {
        private val OPAQUE_PATTERN = Regex("[0-9a-f]{64}")
        private const val DOMAIN = "openeden-conversation-cache-identity-v1:"

        fun fromAuthoritativeSessionId(sessionId: String): ConversationCacheIdentity {
            require(sessionId.isNotBlank()) { "Authoritative session identity must not be blank" }
            return ConversationCacheIdentity(Sha256.hex("$DOMAIN$sessionId".encodeToByteArray()))
        }
    }
}
