package io.openeden.transcript

interface TranscriptStore {
    suspend fun activeIncarnation(): ActiveIncarnation

    suspend fun append(turn: ConversationTurn)

    suspend fun page(
        limit: Int,
        before: HistoryCursor? = null,
    ): ConversationHistoryPage
}
