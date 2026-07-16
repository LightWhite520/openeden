package io.openeden.relationship

class RelationshipRoleResolver(
    private val host: HostIdentity?,
    private val hostAddress: String? = null,
) {
    init {
        require(hostAddress == null || host != null) { "Host address requires a configured host" }
        require(hostAddress == null || hostAddress.isNotBlank()) { "Host address must not be blank" }
    }

    fun resolve(platform: String, userId: String): ResolvedRelationship =
        if (userId != INTERNAL_SENDER_ID && host?.platform == platform && host.userId == userId) {
            ResolvedRelationship(RelationshipRole.HOST, hostAddress)
        } else {
            ResolvedRelationship(RelationshipRole.INTERLOCUTOR, null)
        }

    private companion object {
        const val INTERNAL_SENDER_ID = "INTERNAL"
    }
}
