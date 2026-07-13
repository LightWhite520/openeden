package io.openeden.relationship

interface RelationshipStateStore {
    suspend fun readOrCreate(sessionId: String, userId: String, nowMs: Long = 0L): RelationshipState
    suspend fun write(state: RelationshipState)
    suspend fun reset(sessionId: String, userId: String)
}
