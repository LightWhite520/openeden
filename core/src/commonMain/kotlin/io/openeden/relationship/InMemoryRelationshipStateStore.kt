package io.openeden.relationship

class InMemoryRelationshipStateStore : RelationshipStateStore {
    private val states = mutableMapOf<String, RelationshipState>()

    override suspend fun readOrCreate(sessionId: String, userId: String, nowMs: Long): RelationshipState =
        states.getOrPut(relationshipKey(sessionId, userId)) {
            RelationshipState.neutral(sessionId, userId, nowMs)
        }

    override suspend fun write(state: RelationshipState) {
        states[relationshipKey(state.sessionId, state.userId)] = state
    }

    override suspend fun reset(
        incarnationId: String,
        canonicalSubjectId: String,
        sourceTurnId: String,
        eventId: String,
        createdAtMs: Long,
    ): RelationshipState = super<RelationshipStateStore>.reset(
        incarnationId,
        canonicalSubjectId,
        sourceTurnId,
        eventId,
        createdAtMs,
    )
}
