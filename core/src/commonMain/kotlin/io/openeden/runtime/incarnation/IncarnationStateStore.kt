package io.openeden.runtime.incarnation

import io.openeden.bio.BioVector
import io.openeden.persona.PersonaMode
import io.openeden.persona.PersonaSubState
import io.openeden.runtime.affect.OmegaState
import io.openeden.transcript.ConversationTurn
import io.openeden.transcript.TranscriptStore
import io.openeden.transcript.TurnCommitOutcome

interface IncarnationStateStore {
    suspend fun read(incarnationId: String): IncarnationState

    suspend fun readOrCreate(
        incarnationId: String,
        personaMode: PersonaMode,
        personaStartSubState: PersonaSubState,
    ): IncarnationState

    suspend fun write(state: IncarnationState)

    fun commitsTo(transcriptStore: TranscriptStore): Boolean = false

    suspend fun writeCommittedTurn(
        state: IncarnationState,
        turn: ConversationTurn,
    ): TurnCommitOutcome = error("Incarnation state store does not support atomic turn commits")

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
