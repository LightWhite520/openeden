package io.openeden.relationship

data class ResolvedRelationship(
    val role: RelationshipRole,
    val address: String?,
) {
    init {
        require(address == null || address.isNotBlank()) { "Relationship address must not be blank" }
        require(address == null || role == RelationshipRole.HOST) { "Only HOST may carry an address" }
    }
}
