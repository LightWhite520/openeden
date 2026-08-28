package io.openeden.relationship

data class RelationshipState(
    val incarnationId: String,
    val canonicalSubjectId: String,
    val trust: Float = 0.5f,
    val familiarity: Float = 0.0f,
    val safety: Float = 0.5f,
    val boundarySensitivity: Float = 0.0f,
    val unresolvedTension: Float = 0.0f,
    val reciprocalInterest: Float = 0.0f,
    val evidenceCount: Long = 0L,
    val updatedAtMs: Long = 0L,
    val facts: RelationshipFacts = RelationshipFacts(),
    val events: List<RelationshipEvent> = emptyList(),
) {
    init {
        require(incarnationId.isNotBlank()) { "incarnationId must not be blank" }
        require(canonicalSubjectId.isNotBlank()) { "canonicalSubjectId must not be blank" }
        require(evidenceCount >= 0L) { "evidenceCount must not be negative" }
        listOf(
            trust to "trust",
            familiarity to "familiarity",
            safety to "safety",
            boundarySensitivity to "boundarySensitivity",
            unresolvedTension to "unresolvedTension",
            reciprocalInterest to "reciprocalInterest",
        ).forEach { (value, name) ->
            require(value.isFinite()) { "$name must be finite" }
            require(value in 0.0f..1.0f) { "$name must be in [0, 1]" }
        }
    }

    @Deprecated("Use incarnationId")
    val sessionId: String get() = incarnationId

    @Deprecated("Use canonicalSubjectId")
    val userId: String get() = canonicalSubjectId

    fun apply(evidence: RelationshipEvidence, nowMs: Long): RelationshipState {
        var trustDelta = 0.0f
        var familiarityDelta = 0.0f
        var safetyDelta = 0.0f
        var boundaryDelta = 0.0f
        var tensionDelta = 0.0f
        when (evidence) {
            RelationshipEvidence.RESPECTED_PREFERENCE -> safetyDelta = 0.02f
            RelationshipEvidence.CORRECTED_MISUNDERSTANDING -> {
                trustDelta = 0.015f
                tensionDelta = -0.03f
            }
            RelationshipEvidence.REPEATED_CONSISTENCY -> {
                trustDelta = 0.01f
                safetyDelta = 0.01f
                familiarityDelta = 0.01f
            }
            RelationshipEvidence.BOUNDARY_REQUEST -> boundaryDelta = 0.08f
            RelationshipEvidence.BOUNDARY_VIOLATION -> {
                boundaryDelta = 0.15f
                tensionDelta = 0.15f
                safetyDelta = -0.08f
            }
            RelationshipEvidence.CONFLICT -> {
                tensionDelta = 0.12f
                trustDelta = -0.04f
            }
            RelationshipEvidence.REPAIR -> {
                tensionDelta = -0.08f
                trustDelta = 0.02f
                safetyDelta = 0.02f
            }
        }
        return copy(
            trust = (trust + trustDelta).coerceIn(0.0f, 1.0f),
            familiarity = (familiarity + familiarityDelta).coerceIn(0.0f, 1.0f),
            safety = (safety + safetyDelta).coerceIn(0.0f, 1.0f),
            boundarySensitivity = (boundarySensitivity + boundaryDelta).coerceIn(0.0f, 1.0f),
            unresolvedTension = (unresolvedTension + tensionDelta).coerceIn(0.0f, 1.0f),
            evidenceCount = evidenceCount + 1L,
            updatedAtMs = nowMs,
        )
    }

    companion object {
        fun neutral(incarnationId: String, canonicalSubjectId: String, nowMs: Long = 0L): RelationshipState =
            RelationshipState(incarnationId = incarnationId, canonicalSubjectId = canonicalSubjectId, updatedAtMs = nowMs)
    }
}
