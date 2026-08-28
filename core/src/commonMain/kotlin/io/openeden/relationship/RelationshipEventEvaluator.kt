package io.openeden.relationship

import kotlinx.serialization.Serializable

interface RelationshipEventEvaluator {
    suspend fun evaluate(turn: RelationshipTurn): RelationshipEvaluation
}

@Serializable
data class RelationshipEvaluation(
    val events: List<RelationshipEvent>,
    val confidence: Float,
) {
    init {
        require(confidence.isFinite() && confidence in 0.0f..1.0f) { "confidence must be in [0, 1]" }
    }

    val committableEvents: List<RelationshipEvent>
        get() = events.takeIf { confidence >= COMMIT_CONFIDENCE } ?: emptyList()

    private companion object {
        const val COMMIT_CONFIDENCE = 0.75f
    }
}

@Serializable
data class RelationshipTurn(
    val sourceTurnId: String,
    val incarnationId: String,
    val subjectId: String,
    val userText: String,
    val assistantText: String,
    val completedAtMs: Long,
) {
    init {
        require(sourceTurnId.isNotBlank()) { "sourceTurnId must not be blank" }
        require(incarnationId.isNotBlank()) { "incarnationId must not be blank" }
        require(subjectId.isNotBlank()) { "subjectId must not be blank" }
        require(completedAtMs >= 0L) { "completedAtMs must not be negative" }
    }
}
