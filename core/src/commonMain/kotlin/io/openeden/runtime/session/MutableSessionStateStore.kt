package io.openeden.runtime.session

import io.openeden.persona.PersonaMode
import io.openeden.persona.PersonaSubState
import io.openeden.transcript.ActiveIncarnation
import io.openeden.transcript.AtomicTurnCommitStore
import io.openeden.transcript.ConversationHistoryPage
import io.openeden.transcript.ConversationTurn
import io.openeden.transcript.HistoryCursor
import io.openeden.transcript.InMemoryTranscriptStore
import io.openeden.transcript.TranscriptStore
import kotlinx.coroutines.sync.withLock

class MutableSessionStateStore(
    private val states: MutableMap<String, SessionState> = mutableMapOf(),
    activeIncarnationId: String = "development",
    activeIncarnationCreatedAtMs: Long = 0L,
    transcriptStore: InMemoryTranscriptStore? = null,
) : SessionStateStore, AtomicTurnCommitStore, TranscriptStore {
    private val transcript = transcriptStore ?: InMemoryTranscriptStore(
        activeIncarnationId = activeIncarnationId,
        createdAtMs = activeIncarnationCreatedAtMs,
    )
    private val mutex get() = transcript.atomicMutex

    override suspend fun readOrCreate(
        sessionId: String,
        personaMode: PersonaMode?,
        personaStartSubState: PersonaSubState?,
    ): SessionState = mutex.withLock {
        readOrCreateLocked(sessionId, personaMode, personaStartSubState)
    }

    override suspend fun read(sessionId: String): SessionState = mutex.withLock {
        readOrCreateLocked(sessionId, null, null)
    }

    override suspend fun write(state: SessionState) {
        mutex.withLock {
            validateWriteLocked(state)
            states[state.sessionId] = state
        }
    }

    override fun commitsTo(transcriptStore: TranscriptStore): Boolean =
        transcriptStore === this || transcriptStore === transcript

    override suspend fun writeCommittedTurn(state: SessionState, turn: ConversationTurn) {
        mutex.withLock {
            require(turn.sessionId == state.sessionId) {
                "Turn session '${turn.sessionId}' does not match state session '${state.sessionId}'"
            }
            val activeIncarnation = transcript.activeIncarnationLocked()
            require(turn.incarnationId == activeIncarnation.id) {
                "Turn incarnation '${turn.incarnationId}' does not match active incarnation '${activeIncarnation.id}'"
            }
            transcript.turnByIdLocked(turn.turnId)?.let { existing ->
                require(existing.matchesRetry(turn)) {
                    "Turn ID '${turn.turnId}' already exists with a different payload"
                }
                return@withLock
            }
            validateWriteLocked(state)
            transcript.appendLocked(turn)
            states[state.sessionId] = state
        }
    }

    override suspend fun sessionIds(): Set<String> = mutex.withLock { states.keys.toSet() }

    override suspend fun activeIncarnation(): ActiveIncarnation = transcript.activeIncarnation()

    override suspend fun append(turn: ConversationTurn) = transcript.append(turn)

    override suspend fun page(limit: Int, before: HistoryCursor?): ConversationHistoryPage =
        transcript.page(limit, before)

    private fun readOrCreateLocked(
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

    private fun validateWriteLocked(state: SessionState) {
        states[state.sessionId]?.let { current ->
            require(
                current.personaMode == state.personaMode &&
                    current.personaStartSubState == state.personaStartSubState,
            ) { "Persona mode and starting point are immutable for an existing session" }
        }
    }

    private fun ConversationTurn.matchesRetry(other: ConversationTurn): Boolean =
        copy(completedAtMs = other.completedAtMs) == other
}
