package io.openeden.transcript

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class InMemoryTranscriptStore(
    activeIncarnationId: String,
    createdAtMs: Long = 0L,
    private val promptHistoryAssembler: PromptHistoryAssembler = PromptHistoryAssembler(),
) : TranscriptStore {
    private val activeIncarnation = ActiveIncarnation(activeIncarnationId, createdAtMs)
    internal val atomicMutex = Mutex()
    private val turnsById = mutableMapOf<String, ConversationTurn>()
    private val promptHistoryEpochs = mutableMapOf<String, Long>()
    private val promptHistorySerializerVersions = mutableMapOf<String, Int>()
    private val promptHistoryChunks = mutableMapOf<String, MutableList<PromptHistoryChunk>>()

    override suspend fun activeIncarnation(): ActiveIncarnation = atomicMutex.withLock {
        activeIncarnationLocked()
    }

    override suspend fun append(turn: ConversationTurn) {
        atomicMutex.withLock { appendLocked(turn) }
    }

    override suspend fun recentForSession(sessionId: String, limit: Int): List<ConversationTurn> =
        atomicMutex.withLock {
            val requestedLimit = limit.coerceAtLeast(0)
            if (requestedLimit == 0) return@withLock emptyList()

            turnsById.values
                .asSequence()
                .filter { it.sessionId == sessionId }
                .sortedWith(turnComparator.reversed())
                .take(requestedLimit)
                .toList()
                .asReversed()
        }

    override suspend fun promptHistory(
        sessionId: String,
        requiredTailTurns: Int,
        tokenBudget: Int,
    ): PromptHistorySnapshot = atomicMutex.withLock {
        val epoch = promptHistoryEpochs[sessionId] ?: 0L
        val snapshot = promptHistoryAssembler.assemble(
            sessionId = sessionId,
            turns = turnsById.values.filter { it.sessionId == sessionId },
            requiredTailTurns = requiredTailTurns,
            tokenBudget = tokenBudget,
            existingStableChunks = promptHistoryChunks[promptHistoryKey(sessionId, epoch)].orEmpty(),
            cacheEpoch = epoch,
            storedSerializerVersion = promptHistorySerializerVersions[sessionId]
                ?: promptHistoryAssembler.serializer.serializerVersion,
        )
        promptHistoryEpochs[sessionId] = snapshot.cacheEpoch
        promptHistorySerializerVersions[sessionId] = promptHistoryAssembler.serializer.serializerVersion
        promptHistoryChunks.getOrPut(promptHistoryKey(sessionId, snapshot.cacheEpoch)) { mutableListOf() }
            .apply {
                snapshot.stableChunks.forEach { chunk ->
                    if (none { it.firstTurnId == chunk.firstTurnId }) add(chunk)
                }
            }
        snapshot
    }

    override suspend fun page(
        limit: Int,
        before: HistoryCursor?,
    ): ConversationHistoryPage = atomicMutex.withLock {
        pageLocked(limit, before)
    }

    internal fun activeIncarnationLocked(): ActiveIncarnation = activeIncarnation

    internal fun turnByIdLocked(turnId: String): ConversationTurn? = turnsById[turnId]

    internal fun appendLocked(turn: ConversationTurn) {
        require(turn.incarnationId == activeIncarnation.id) {
            "Turn incarnation '${turn.incarnationId}' does not match active incarnation '${activeIncarnation.id}'"
        }
        val existing = turnsById[turn.turnId]
        require(existing == null || existing == turn) {
            "Turn ID '${turn.turnId}' already exists with a different payload"
        }
        if (existing == null) turnsById[turn.turnId] = turn
    }

    internal fun pageLocked(
        limit: Int,
        before: HistoryCursor?,
    ): ConversationHistoryPage {
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

        return ConversationHistoryPage(
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

    private fun promptHistoryKey(sessionId: String, cacheEpoch: Long): String =
        "$sessionId\u0000$cacheEpoch"

    private companion object {
        const val MIN_PAGE_SIZE = 1
        const val MAX_PAGE_SIZE = 50

        val turnComparator = compareBy<ConversationTurn>(
            ConversationTurn::completedAtMs,
            ConversationTurn::turnId,
        )
    }
}
