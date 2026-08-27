package io.openeden.runtime.incarnation

import io.openeden.bio.BioVector
import io.openeden.persona.PersonaMode
import io.openeden.persona.PersonaSubState
import io.openeden.runtime.affect.OmegaState

interface IncarnationStateStore {
    suspend fun read(incarnationId: String): IncarnationState

    suspend fun readOrCreate(
        incarnationId: String,
        personaMode: PersonaMode,
        personaStartSubState: PersonaSubState,
    ): IncarnationState

    suspend fun write(state: IncarnationState)

    companion object {
        fun neutral(
            incarnationId: String,
            personaMode: PersonaMode,
            personaStartSubState: PersonaSubState,
        ): IncarnationState = IncarnationState(
            incarnationId = incarnationId,
            vector = BioVector.Neutral,
            origin = BioVector.Neutral,
            omega = OmegaState(0.0f),
            evolutionIndex = 0,
            personaMode = personaMode,
            personaStartSubState = personaStartSubState,
            lastUserActivityMs = null,
            lastRuntimeTickAtMs = null,
            shockState = null,
        )
    }
}
