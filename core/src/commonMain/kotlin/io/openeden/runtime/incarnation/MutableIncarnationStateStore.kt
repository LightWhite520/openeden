package io.openeden.runtime.incarnation

import io.openeden.persona.PersonaMode
import io.openeden.persona.PersonaSubState
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MutableIncarnationStateStore(
    private val states: MutableMap<String, IncarnationState> = mutableMapOf(),
) : IncarnationStateStore {
    private val mutex = Mutex()

    override suspend fun read(incarnationId: String): IncarnationState = mutex.withLock {
        checkNotNull(states[incarnationId]) { "No Bio state exists for incarnation '$incarnationId'" }
    }

    override suspend fun readOrCreate(
        incarnationId: String,
        personaMode: PersonaMode,
        personaStartSubState: PersonaSubState,
    ): IncarnationState = mutex.withLock {
        states.getOrPut(incarnationId) {
            IncarnationStateStore.neutral(incarnationId, personaMode, personaStartSubState)
        }
    }

    override suspend fun write(state: IncarnationState) {
        mutex.withLock {
            states[state.incarnationId]?.let { current ->
                require(
                    current.personaMode == state.personaMode &&
                        current.personaStartSubState == state.personaStartSubState,
                ) { "Persona mode and starting point are immutable for an existing incarnation" }
            }
            states[state.incarnationId] = state
        }
    }
}
