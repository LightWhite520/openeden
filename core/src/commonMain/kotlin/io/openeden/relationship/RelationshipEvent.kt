package io.openeden.relationship

data class RelationshipEvent(
    val eventId: String,
    val incarnationId: String,
    val canonicalSubjectId: String,
    val sourceTurnId: String,
    val type: RelationshipEventType,
    val confidence: Float,
    val evidenceDigest: String,
    val createdAtMs: Long,
    val supersedesEventId: String? = null,
    val preferredAddress: String? = null,
) {
    init {
        require(eventId.isNotBlank()) { "eventId must not be blank" }
        require(incarnationId.isNotBlank()) { "incarnationId must not be blank" }
        require(canonicalSubjectId.isNotBlank()) { "canonicalSubjectId must not be blank" }
        require(sourceTurnId.isNotBlank()) { "sourceTurnId must not be blank" }
        require(confidence.isFinite() && confidence in 0.0f..1.0f) { "confidence must be in [0, 1]" }
        require(evidenceDigest.isNotBlank()) { "evidenceDigest must not be blank" }
        require(createdAtMs >= 0L) { "createdAtMs must not be negative" }
        require(preferredAddress == null || preferredAddress.isNotBlank()) { "preferredAddress must not be blank" }
    }

    fun idempotencyKey(): String = listOf(sourceTurnId, type.name, incarnationId, canonicalSubjectId).joinToString("\u0000")
}

enum class RelationshipEventType {
    ACQUAINTANCE,
    PREFERENCE_CONFIRMED,
    PREFERENCE_RESPECTED,
    BOUNDARY_REQUEST,
    BOUNDARY_VIOLATION,
    USER_CONFESSION,
    ATRI_ACCEPTANCE,
    MUTUAL_COMMITMENT,
    CONFLICT,
    REPAIR,
    ADDRESS_PREFERENCE,
    PROMISE_CREATED,
    PROMISE_FULFILLED,
    PROMISE_REVOKED,
    RELATIONSHIP_ENDED,
    RESET,
}
