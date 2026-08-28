package io.openeden.transcript

import io.openeden.memory.MemoryEntry
import io.openeden.relationship.RelationshipEvaluation
import io.openeden.relationship.RelationshipTurn
import kotlinx.serialization.Serializable

@Serializable
data class TurnPostCommitPlan(
    val turnId: String,
    val relationshipTurn: RelationshipTurn? = null,
    val relationshipEvaluation: RelationshipEvaluation? = null,
    val relationshipEvaluationDegraded: Boolean = false,
    val rawMemory: MemoryEntry? = null,
) {
    init {
        require(turnId.isNotBlank()) { "turnId must not be blank" }
    }

    val requiredStages: List<TurnPostCommitStage>
        get() = buildList {
            if (relationshipTurn != null || relationshipEvaluation != null) add(TurnPostCommitStage.RELATIONSHIP)
            if (rawMemory != null) {
                add(TurnPostCommitStage.RAW_MEMORY)
                if (rawMemory.metadata.deltaVec.toList().any { kotlin.math.abs(it) > 0.0f }) {
                    add(TurnPostCommitStage.DIARY)
                }
                add(TurnPostCommitStage.CENTROID)
            }
        }
}
