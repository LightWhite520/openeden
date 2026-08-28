package io.openeden.relationship

data class RelationshipContinuousAccumulator(
    val trust: Float,
    val familiarity: Float,
    val safety: Float,
    val boundarySensitivity: Float,
    val unresolvedTension: Float,
    val reciprocalInterest: Float,
) {
    init {
        listOf(
            trust to "trust",
            familiarity to "familiarity",
            safety to "safety",
            boundarySensitivity to "boundarySensitivity",
            unresolvedTension to "unresolvedTension",
            reciprocalInterest to "reciprocalInterest",
        ).forEach { (value, name) -> require(value.isFinite()) { "$name accumulator must be finite" } }
    }

    fun apply(evidence: RelationshipEvidence): RelationshipContinuousAccumulator = when (evidence) {
        RelationshipEvidence.ORDINARY_INTERACTION -> copy(familiarity = familiarity + 0.005f)
        RelationshipEvidence.RESPECTED_PREFERENCE -> copy(safety = safety + 0.02f)
        RelationshipEvidence.CORRECTED_MISUNDERSTANDING -> copy(
            trust = trust + 0.015f,
            unresolvedTension = unresolvedTension - 0.03f,
        )
        RelationshipEvidence.REPEATED_CONSISTENCY -> copy(
            trust = trust + 0.01f,
            familiarity = familiarity + 0.01f,
            safety = safety + 0.01f,
        )
        RelationshipEvidence.RECIPROCAL_INTEREST -> copy(reciprocalInterest = reciprocalInterest + 0.1f)
        RelationshipEvidence.BOUNDARY_REQUEST -> copy(boundarySensitivity = boundarySensitivity + 0.08f)
        RelationshipEvidence.BOUNDARY_VIOLATION -> copy(
            safety = safety - 0.08f,
            boundarySensitivity = boundarySensitivity + 0.15f,
            unresolvedTension = unresolvedTension + 0.15f,
        )
        RelationshipEvidence.CONFLICT -> copy(
            trust = trust - 0.04f,
            unresolvedTension = unresolvedTension + 0.12f,
        )
        RelationshipEvidence.REPAIR -> copy(
            trust = trust + 0.02f,
            safety = safety + 0.02f,
            unresolvedTension = unresolvedTension - 0.08f,
        )
    }

    operator fun plus(other: RelationshipContinuousAccumulator): RelationshipContinuousAccumulator =
        RelationshipContinuousAccumulator(
            trust = trust + other.trust,
            familiarity = familiarity + other.familiarity,
            safety = safety + other.safety,
            boundarySensitivity = boundarySensitivity + other.boundarySensitivity,
            unresolvedTension = unresolvedTension + other.unresolvedTension,
            reciprocalInterest = reciprocalInterest + other.reciprocalInterest,
        )

    operator fun minus(other: RelationshipContinuousAccumulator): RelationshipContinuousAccumulator =
        RelationshipContinuousAccumulator(
            trust = trust - other.trust,
            familiarity = familiarity - other.familiarity,
            safety = safety - other.safety,
            boundarySensitivity = boundarySensitivity - other.boundarySensitivity,
            unresolvedTension = unresolvedTension - other.unresolvedTension,
            reciprocalInterest = reciprocalInterest - other.reciprocalInterest,
        )

    companion object {
        const val CURRENT_VERSION: Int = 1

        val Zero = RelationshipContinuousAccumulator(
            trust = 0.0f,
            familiarity = 0.0f,
            safety = 0.0f,
            boundarySensitivity = 0.0f,
            unresolvedTension = 0.0f,
            reciprocalInterest = 0.0f,
        )

        fun from(state: RelationshipState): RelationshipContinuousAccumulator = RelationshipContinuousAccumulator(
            trust = state.trust,
            familiarity = state.familiarity,
            safety = state.safety,
            boundarySensitivity = state.boundarySensitivity,
            unresolvedTension = state.unresolvedTension,
            reciprocalInterest = state.reciprocalInterest,
        )
    }
}
