package io.openeden.runtime.session

import io.openeden.persona.PersonaSubState
import io.openeden.persona.PersonaMode
import io.openeden.transcript.ActiveIncarnation
import io.openeden.transcript.AtomicTurnCommitStore
import io.openeden.transcript.ConversationHistoryPage
import io.openeden.transcript.ConversationTurn
import io.openeden.transcript.HistoryCursor
import io.openeden.transcript.InvalidHistoryCursorException
import io.openeden.transcript.TranscriptStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MutableSessionStateStore(
    private val states: MutableMap<String, SessionState> = mutableMapOf(),
    activeIncarnationId: String = "development",
    activeIncarnationCreatedAtMs: Long = 0L,
    private val transcriptStore: TranscriptStore? = null,
) : SessionStateStore, AtomicTurnCommitStore, TranscriptStore {
    private val mutex = Mutex()
    private val activeIncarnation = ActiveIncarnation(activeIncarnationId, activeIncarnationCreatedAtMs)
    private val turnsById = mutableMapOf<String, ConversationTurn>()

    override suspend fun readOrCreate(
        sessionId: String,
        personaMode: PersonaMode?,
        personaStartSubState: PersonaSubState?,
    ): SessionState = mutex.withLock {
        readOrCreateUnlocked(sessionId, personaMode, personaStartSubState)
    }

    override suspend fun read(sessionId: String): SessionState = mutex.withLock {
        readOrCreateUnlocked(sessionId, null, null)
    }

    override suspend fun write(state: SessionState) {
        mutex.withLock { writeUnlocked(state) }
    }

    override suspend fun writeCommittedTurn(state: SessionState, turn: ConversationTurn) {
        mutex.withLock {
            require(turn.sessionId == state.sessionId) {
                "Turn session '${turn.sessionId}' does not match state session '${state.sessionId}'"
            }
            val expectedIncarnation = transcriptStore?.activeIncarnation() ?: activeIncarnation
            require(turn.incarnationId == expectedIncarnation.id) {
                "Turn incarnation '${turn.incarnationId}' does not match active incarnation '${expectedIncarnation.id}'"
            }
            turnsById[turn.turnId]?.let { existing ->
                require(existing.matchesRetry(turn)) {
                    "Turn ID '${turn.turnId}' already exists with a different payload"
                }
                return@withLock
            }
            validateWriteUnlocked(state)
            transcriptStore?.append(turn)
            states[state.sessionId] = state
            turnsById[turn.turnId] = turn
        }
    }

    override suspend fun sessionIds(): Set<String> = mutex.withLock { states.keys.toSet() }

    override suspend fun activeIncarnation(): ActiveIncarnation = mutex.withLock {
        transcriptStore?.activeIncarnation() ?: activeIncarnation
    }

    override suspend fun append(turn: ConversationTurn) {
        mutex.withLock {
            transcriptStore?.let { delegate ->
                delegate.append(turn)
                return@withLock
            }
            require(turn.incarnationId == activeIncarnation.id) {
                "Turn incarnation '${turn.incarnationId}' does not match active incarnation '${activeIncarnation.id}'"
            }
            val existing = turnsById[turn.turnId]
            require(existing == null || existing == turn) {
                "Turn ID '${turn.turnId}' already exists with a different payload"
            }
            if (existing == null) turnsById[turn.turnId] = turn
        }
    }

    override suspend fun page(limit: Int, before: HistoryCursor?): ConversationHistoryPage = mutex.withLock {
        transcriptStore?.let { delegate -> return@withLock delegate.page(limit, before) }
        if (before != null && before.incarnationId != activeIncarnation.id) {
            throw InvalidHistoryCursorException(
                "Cursor incarnation '${before.incarnationId}' does not match active incarnation '${activeIncarnation.id}'",
            )
        }
        val clampedLimit = limit.coerceIn(1, 50)
        val candidates = turnsById.values
            .asSequence()
            .filter { turn -> before == null || turn.isBefore(before) }
            .sortedWith(turnComparator.reversed())
            .take(clampedLimit + 1)
            .toList()
        val hasMore = candidates.size > clampedLimit
        val turns = candidates.take(clampedLimit).asReversed()
        ConversationHistoryPage(
            turns = turns,
            before = if (hasMore) turns.first().toCursor() else null,
            hasMore = hasMore,
        )
    }

    private fun readOrCreateUnlocked(
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

    private fun writeUnlocked(state: SessionState) {
        validateWriteUnlocked(state)
        states[state.sessionId] = state
    }

    private fun validateWriteUnlocked(state: SessionState) {
        states[state.sessionId]?.let { current ->
            require(
                current.personaMode == state.personaMode &&
                    current.personaStartSubState == state.personaStartSubState,
            ) { "Persona mode and starting point are immutable for an existing session" }
        }
    }

    internal fun isTranscriptBackedBy(candidate: TranscriptStore): Boolean =
        candidate === this || transcriptStore === candidate

    private fun ConversationTurn.matchesRetry(other: ConversationTurn): Boolean =
        copy(completedAtMs = other.completedAtMs) == other

    private fun ConversationTurn.isBefore(cursor: HistoryCursor): Boolean =
        completedAtMs < cursor.completedAtMs ||
            (completedAtMs == cursor.completedAtMs && turnId < cursor.turnId)

    private fun ConversationTurn.toCursor() = HistoryCursor(
        incarnationId = activeIncarnation.id,
        completedAtMs = completedAtMs,
        turnId = turnId,
    )

    private companion object {
        val turnComparator = compareBy<ConversationTurn>(
            ConversationTurn::completedAtMs,
            ConversationTurn::turnId,
        )
    }
}
