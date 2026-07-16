package io.openeden.transcript

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class InMemoryTranscriptStore(
    activeIncarnationId: String,
    createdAtMs: Long = 0L,
) : TranscriptStore {
    private val activeIncarnation = ActiveIncarnation(activeIncarnationId, createdAtMs)
    private val mutex = Mutex()
    private val turnsById = mutableMapOf<String, ConversationTurn>()

    override suspend fun activeIncarnation(): ActiveIncarnation = mutex.withLock {
        activeIncarnation
    }

    override suspend fun append(turn: ConversationTurn) {
        mutex.withLock {
            val existing = turnsById[turn.turnId]
            require(existing == null || existing == turn) {
                "Turn ID '${turn.turnId}' already exists with a different payload"
            }
            if (existing == null) {
                turnsById[turn.turnId] = turn
            }
        }
    }

    override suspend fun page(
        limit: Int,
        before: HistoryCursor?,
    ): ConversationHistoryPage = mutex.withLock {
        if (before != null && before.incarnationId != activeIncarnation.id) {
            throw InvalidHistoryCursorException(
                "Cursor incarnation '${before.incarnationId}' does not match active incarnation '${activeIncarnation.id}'",
            )
        }

        val clampedLimit = limit.coerceIn(MIN_PAGE_SIZE, MAX_PAGE_SIZE)
        val candidates = turnsById.values
            .asSequence()
            .filter { before == null || it.isBefore(before) }
            .sortedWith(turnComparator.reversed())
            .take(clampedLimit + 1)
            .toList()
        val hasMore = candidates.size > clampedLimit
        val turns = candidates.take(clampedLimit).asReversed()
        val nextCursor = if (hasMore) turns.first().toCursor() else null

        ConversationHistoryPage(
            turns = turns,
            before = nextCursor,
            hasMore = hasMore,
        )
    }

    private fun ConversationTurn.isBefore(cursor: HistoryCursor): Boolean =
        completedAtMs < cursor.completedAtMs ||
            (completedAtMs == cursor.completedAtMs && turnId < cursor.turnId)

    private fun ConversationTurn.toCursor(): HistoryCursor = HistoryCursor(
        incarnationId = activeIncarnation.id,
        completedAtMs = completedAtMs,
        turnId = turnId,
    )

    private companion object {
        const val MIN_PAGE_SIZE = 1
        const val MAX_PAGE_SIZE = 50

        val turnComparator = compareBy<ConversationTurn>(
            ConversationTurn::completedAtMs,
            ConversationTurn::turnId,
        )
    }
}
