package io.openeden.relationship

interface RelationshipStateStore {
    suspend fun readOrCreate(incarnationId: String, canonicalSubjectId: String, nowMs: Long = 0L): RelationshipState
    suspend fun write(state: RelationshipState)

    suspend fun append(event: RelationshipEvent): RelationshipState {
        val state = readOrCreate(event.incarnationId, event.canonicalSubjectId, event.createdAtMs)
        return RelationshipReducer.reduce(state, listOf(event)).also(::write)
    }

    suspend fun events(incarnationId: String, canonicalSubjectId: String): List<RelationshipEvent> =
        readOrCreate(incarnationId, canonicalSubjectId).events

    suspend fun correct(correction: RelationshipCorrection): RelationshipState = append(correction.event)

    suspend fun reset(
        incarnationId: String,
        canonicalSubjectId: String,
        sourceTurnId: String = "legacy-reset:$incarnationId:$canonicalSubjectId",
        eventId: String = "legacy-reset:$incarnationId:$canonicalSubjectId",
        createdAtMs: Long = 0L,
    ): RelationshipState = append(
        RelationshipEvent(
            eventId = eventId,
            incarnationId = incarnationId,
            canonicalSubjectId = canonicalSubjectId,
            sourceTurnId = sourceTurnId,
            type = RelationshipEventType.RESET,
            confidence = 1.0f,
            evidenceDigest = "explicit reset",
            createdAtMs = createdAtMs,
        ),
    )
}
