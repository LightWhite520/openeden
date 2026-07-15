package io.openeden.runtime.session

import io.openeden.runtime.affect.OmegaState
import io.openeden.runtime.affect.ShockState
import io.openeden.persona.PersonaSubState
import io.openeden.persona.PersonaMode

import io.openeden.bio.BioVector

interface SessionStateStore {
    suspend fun read(sessionId: String): SessionState

    /** Return the existing session, or create a neutral one with an immutable persona starting point. */
    suspend fun readOrCreate(
        sessionId: String,
        personaMode: PersonaMode? = null,
        personaStartSubState: PersonaSubState? = null,
    ): SessionState = read(sessionId)

    suspend fun write(state: SessionState)

    /** All session ids the store currently knows about — the heartbeat scheduler enumerates these. */
    suspend fun sessionIds(): Set<String>

    companion object {
        fun neutral(
            sessionId: String,
            personaStartSubState: PersonaSubState = PersonaSubState.PRE_COMMAND,
            personaMode: PersonaMode = PersonaMode.GROWTH,
        ): SessionState = SessionState(
            sessionId = sessionId,
            vector = BioVector.Neutral,
            origin = BioVector.Neutral,
            omega = OmegaState(0.0f),
            shockState = null,
            evolutionIndex = 0,
            personaMode = personaMode,
            personaStartSubState = personaStartSubState,
            lastUserActivityMs = null,
        )
    }
}
