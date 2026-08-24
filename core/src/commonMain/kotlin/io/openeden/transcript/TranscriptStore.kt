package io.openeden.transcript

interface TranscriptStore {
    suspend fun activeIncarnation(): ActiveIncarnation

    suspend fun append(turn: ConversationTurn)

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

    suspend fun page(
        limit: Int,
        before: HistoryCursor? = null,
    ): ConversationHistoryPage

    private companion object {
        const val DEFAULT_PAGE_SIZE = 50
    }
}
