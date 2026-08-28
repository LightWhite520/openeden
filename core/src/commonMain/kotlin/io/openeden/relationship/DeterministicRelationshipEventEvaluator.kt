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
        return exactSignals[normalized]
    }

    private fun RelationshipTurn.toEvent(type: RelationshipEventType): RelationshipEvent = RelationshipEvent(
        eventId = "$sourceTurnId:${type.name}",
        incarnationId = incarnationId,
        canonicalSubjectId = subjectId,
        sourceTurnId = sourceTurnId,
        type = type,
        confidence = 1.0f,
        evidenceDigest = "deterministic exact relationship signal",
        createdAtMs = completedAtMs,
    )

    private companion object {
        val proposalExclusions = setOf(
            "要不要吃饭",
            "你要不要抱我",
            "要不要说对不起",
            "你记得吗",
        )
        val negationExclusions = setOf(
            "我不是不要你",
            "我不是在道歉",
            "我不是每次都这样",
        )
        val exactSignals = mapOf(
            "不要这样，请停下" to RelationshipEventType.BOUNDARY_REQUEST,
            "别这样，请停下" to RelationshipEventType.BOUNDARY_REQUEST,
            "对不起，我刚才弄错了" to RelationshipEventType.REPAIR,
            "抱歉，我误会了" to RelationshipEventType.REPAIR,
            "你一直都记得我们的约定" to RelationshipEventType.REPEATED_CONSISTENCY,
            "你每次都做到答应的事" to RelationshipEventType.REPEATED_CONSISTENCY,
        )
    }
}
