package io.openeden.runtime.incarnation

import io.openeden.persona.PersonaMode
import io.openeden.persona.PersonaSubState
import io.openeden.transcript.ConversationTurn
import io.openeden.transcript.AtomicTurnCommitStore
import io.openeden.transcript.InMemoryTranscriptStore
import io.openeden.transcript.TranscriptStore
import io.openeden.transcript.TurnCommitOutcome
import io.openeden.transcript.TurnPostCommitPlan
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MutableIncarnationStateStore(
    private val states: MutableMap<String, IncarnationState> = mutableMapOf(),
    private val transcriptStore: InMemoryTranscriptStore? = null,
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

    override fun commitsTo(transcriptStore: TranscriptStore): Boolean = this.transcriptStore?.let { backing ->
        transcriptStore === backing ||
            (transcriptStore as? AtomicTurnCommitStore)?.commitsTo(backing) == true
    } == true

    override suspend fun writeCommittedTurn(
        state: IncarnationState,
        turn: ConversationTurn,
        postCommitPlan: TurnPostCommitPlan,
    ): TurnCommitOutcome {
        val transcript = checkNotNull(transcriptStore) {
            "Public turns require an atomic incarnation state store"
        }
        return transcript.atomicMutex.withLock {
            require(turn.incarnationId == transcript.activeIncarnationLocked().id) {
                "Turn incarnation '${turn.incarnationId}' does not match active incarnation"
            }
            transcript.turnByIdLocked(turn.turnId)?.let { existing ->
                require(existing.matchesRetry(turn)) {
                    "Turn ID '${turn.turnId}' already exists with a different payload"
                }
                transcript.preparePostCommitLocked(postCommitPlan)
                return@withLock TurnCommitOutcome.ALREADY_COMMITTED
            }
            mutex.withLock {
                states[state.incarnationId]?.let { current ->
                    require(
                        current.personaMode == state.personaMode &&
                            current.personaStartSubState == state.personaStartSubState,
                    ) { "Persona mode and starting point are immutable for an existing incarnation" }
                }
                transcript.appendLocked(turn)
                transcript.preparePostCommitLocked(postCommitPlan)
                states[state.incarnationId] = state
            }
            TurnCommitOutcome.INSERTED
        }
    }

    private fun ConversationTurn.matchesRetry(other: ConversationTurn): Boolean =
        copy(completedAtMs = other.completedAtMs) == other
}
