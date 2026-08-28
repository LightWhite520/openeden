package io.openeden.transcript

import io.openeden.relationship.RelationshipEvaluation

interface TranscriptStore {
    suspend fun activeIncarnation(): ActiveIncarnation

    suspend fun append(turn: ConversationTurn)

    suspend fun findByTurnId(turnId: String): ConversationTurn? {
        var before: HistoryCursor? = null
        do {
            val page = page(limit = DEFAULT_PAGE_SIZE, before = before)
            page.turns.firstOrNull { it.turnId == turnId }?.let { return it }
            before = page.before
        } while (page.hasMore)
        return null
    }

    suspend fun postCommitState(turnId: String): TurnPostCommitState? = null

    suspend fun persistRelationshipEvaluation(
        turnId: String,
        evaluation: RelationshipEvaluation,
    ): RelationshipEvaluation = error("Transcript store does not support durable relationship evaluation")

    suspend fun markPostCommitStageCompleted(turnId: String, stage: TurnPostCommitStage) {
        error("Transcript store does not support durable post-commit stages")
    }

    /**
     * Returns the newest completed turns for one conversation session in chronological order.
     * The default implementation keeps lightweight TranscriptStore test doubles compatible;
     * persistent implementations should override it with a session-scoped query.
     */
    suspend fun recentForSession(sessionId: String, limit: Int): List<ConversationTurn> {
        val requestedLimit = limit.coerceAtLeast(0)
        if (requestedLimit == 0) return emptyList()

        val pages = mutableListOf<List<ConversationTurn>>()
        var before: HistoryCursor? = null
        var page: ConversationHistoryPage
        do {
            page = page(limit = DEFAULT_PAGE_SIZE, before = before)
            pages += page.turns.filter { it.sessionId == sessionId }
            before = page.before
        } while (page.hasMore)

        return pages.asReversed().flatten().takeLast(requestedLimit)
    }

    suspend fun promptHistory(
        sessionId: String,
        requiredTailTurns: Int,
        tokenBudget: Int,
    ): PromptHistorySnapshot = PromptHistoryAssembler().assemble(
        sessionId = sessionId,
        turns = recentForSession(sessionId, Int.MAX_VALUE),
        requiredTailTurns = requiredTailTurns,
        tokenBudget = tokenBudget,
    )

    suspend fun page(
        limit: Int,
        before: HistoryCursor? = null,
    ): ConversationHistoryPage

    private companion object {
        const val DEFAULT_PAGE_SIZE = 50
    }
}
