package io.openeden.runtime

import io.openeden.bio.BioVector

interface SessionStateStore {
    suspend fun read(sessionId: String): SessionState

    /** Return the existing session, or a fresh neutral one if absent. Lets the pipeline depend on
     *  this interface rather than a concrete store. Implementations need not persist on read. */
    suspend fun readOrCreate(sessionId: String): SessionState = read(sessionId)

    suspend fun write(state: SessionState)

    /** All session ids the store currently knows about — the heartbeat scheduler enumerates these. */
    suspend fun sessionIds(): Set<String>

    companion object {
        fun neutral(sessionId: String): SessionState = SessionState(
            sessionId = sessionId,
            vector = BioVector.Neutral,
            origin = BioVector.Neutral,
            omega = OmegaState(0.0f),
            shockState = null,
            evolutionIndex = 0,
            lastUserActivityMs = null,
        )
    }
}
