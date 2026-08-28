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
    val continuousAccumulator: RelationshipContinuousAccumulator? = null,
    val continuousAccumulatorVersion: Int = 0,
    val continuousBaselineEventIds: Set<String> = emptySet(),
) {
    init {
        require(incarnationId.isNotBlank()) { "incarnationId must not be blank" }
        require(canonicalSubjectId.isNotBlank()) { "canonicalSubjectId must not be blank" }
        require(evidenceCount >= 0L) { "evidenceCount must not be negative" }
        require(continuousAccumulatorVersion >= 0) { "continuousAccumulatorVersion must not be negative" }
        require(continuousAccumulator != null || continuousAccumulatorVersion == 0) {
            "continuousAccumulatorVersion requires an accumulator"
        }
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
        val accumulated = (continuousAccumulator ?: RelationshipContinuousAccumulator.from(this)).apply(evidence)
        return copy(
            trust = accumulated.trust.coerceIn(0.0f, 1.0f),
            familiarity = accumulated.familiarity.coerceIn(0.0f, 1.0f),
            safety = accumulated.safety.coerceIn(0.0f, 1.0f),
            boundarySensitivity = accumulated.boundarySensitivity.coerceIn(0.0f, 1.0f),
            unresolvedTension = accumulated.unresolvedTension.coerceIn(0.0f, 1.0f),
            reciprocalInterest = accumulated.reciprocalInterest.coerceIn(0.0f, 1.0f),
            evidenceCount = evidenceCount + 1L,
            updatedAtMs = nowMs,
            continuousAccumulator = accumulated,
            continuousAccumulatorVersion = RelationshipContinuousAccumulator.CURRENT_VERSION,
        )
    }

    companion object {
        fun neutral(incarnationId: String, canonicalSubjectId: String, nowMs: Long = 0L): RelationshipState =
            RelationshipState(incarnationId = incarnationId, canonicalSubjectId = canonicalSubjectId, updatedAtMs = nowMs)
    }
}
