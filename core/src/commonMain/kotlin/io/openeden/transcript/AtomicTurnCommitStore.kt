package io.openeden.transcript

import io.openeden.runtime.session.SessionState

interface AtomicTurnCommitStore {
    suspend fun writeCommittedTurn(
        state: SessionState,
        turn: ConversationTurn,
    )
}
