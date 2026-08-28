package io.openeden.relationship

data class RelationshipFacts(
    val phase: RelationshipPhase = RelationshipPhase.STRANGER,
    val userConfessedAtMs: Long? = null,
    val atriAcceptedAtMs: Long? = null,
    val mutualCommitmentAtMs: Long? = null,
    val preferredAddresses: Set<String> = emptySet(),
)
