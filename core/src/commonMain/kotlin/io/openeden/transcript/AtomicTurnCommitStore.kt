package io.openeden.transcript

import io.openeden.runtime.session.SessionState

interface AtomicTurnCommitStore {
    fun commitsTo(transcriptStore: TranscriptStore): Boolean

    suspend fun writeCommittedTurn(
        state: SessionState,
        turn: ConversationTurn,
    )
}
