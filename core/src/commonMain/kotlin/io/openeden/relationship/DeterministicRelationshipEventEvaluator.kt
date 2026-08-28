package io.openeden.relationship

class DeterministicRelationshipEventEvaluator : RelationshipEventEvaluator {
    override suspend fun evaluate(turn: RelationshipTurn): RelationshipEvaluation {
        val eventType = eventTypeFor(turn.userText)
        return RelationshipEvaluation(
            events = eventType?.let { type -> listOf(turn.toEvent(type)) } ?: emptyList(),
            confidence = 1.0f,
        )
    }

    private fun eventTypeFor(text: String): RelationshipEventType? {
        val normalized = text.trim().removeSuffix("。").removeSuffix("！")
        if (normalized in proposalExclusions || normalized in negationExclusions) return null
        return exactBoundaryRequests[normalized]
    }

    private fun RelationshipTurn.toEvent(type: RelationshipEventType): RelationshipEvent = RelationshipEvent(
        eventId = "$sourceTurnId:${type.name}",
        incarnationId = incarnationId,
        canonicalSubjectId = subjectId,
        sourceTurnId = sourceTurnId,
        type = type,
        confidence = 1.0f,
        evidenceDigest = "deterministic exact boundary request",
        createdAtMs = completedAtMs,
    )

    private companion object {
        val proposalExclusions = setOf("要不要吃饭", "你要不要抱我")
        val negationExclusions = setOf("我不是不要你")
        val exactBoundaryRequests = mapOf(
            "不要这样，请停下" to RelationshipEventType.BOUNDARY_REQUEST,
            "别这样，请停下" to RelationshipEventType.BOUNDARY_REQUEST,
        )
    }
}
