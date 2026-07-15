package io.openeden.runtime.session

import io.openeden.persona.PersonaSubState
import io.openeden.persona.PersonaMode

class MutableSessionStateStore(
    private val states: MutableMap<String, SessionState> = mutableMapOf(),
) : SessionStateStore {
    override suspend fun readOrCreate(
        sessionId: String,
        personaMode: PersonaMode?,
        personaStartSubState: PersonaSubState?,
    ): SessionState = states.getOrPut(sessionId) {
        SessionStateStore.neutral(
            sessionId,
            personaStartSubState ?: PersonaSubState.PRE_COMMAND,
            personaMode ?: PersonaMode.GROWTH,
        )
    }

    override suspend fun read(sessionId: String): SessionState = readOrCreate(sessionId)

    override suspend fun write(state: SessionState) {
        states[state.sessionId]?.let { current ->
            require(
                current.personaMode == state.personaMode &&
                    current.personaStartSubState == state.personaStartSubState,
            ) { "Persona mode and starting point are immutable for an existing session" }
        }
        states[state.sessionId] = state
    }

    override suspend fun sessionIds(): Set<String> = states.keys.toSet()
}
