package io.openeden.identity

class CanonicalSubjectResolver(
    private val bindings: Map<PlatformUser, CanonicalSubjectId> = emptyMap(),
) {
    fun resolve(platform: String, userId: String): CanonicalSubjectId {
        val identity = PlatformUser(platform, userId.ifBlank { ANONYMOUS_SUBJECT })
        return bindings[identity] ?: CanonicalSubjectId("${identity.platform}:${identity.userId}")
    }

    data class PlatformUser(
        val platform: String,
        val userId: String,
    ) {
        init {
            require(platform.isNotBlank()) { "Platform must not be blank" }
            require(userId.isNotBlank()) { "User ID must not be blank" }
        }
    }

    private companion object {
        const val ANONYMOUS_SUBJECT = "anonymous"
    }
}
