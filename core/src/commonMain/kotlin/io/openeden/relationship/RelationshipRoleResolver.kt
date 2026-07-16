package io.openeden.relationship

class RelationshipRoleResolver(
    private val host: HostIdentity?,
) {
    fun resolve(platform: String, userId: String): RelationshipRole =
        if (userId != INTERNAL_SENDER_ID && host?.platform == platform && host.userId == userId) {
            RelationshipRole.HOST
        } else {
            RelationshipRole.INTERLOCUTOR
        }

    private companion object {
        const val INTERNAL_SENDER_ID = "INTERNAL"
    }
}
