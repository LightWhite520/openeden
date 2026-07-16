package io.openeden.runtime.state

import io.openeden.runtime.session.SessionState
import io.openeden.transcript.TurnCommitOutcome

data class VectorWriteResult(
    val state: SessionState,
    val traceTags: Set<String>,
    val turnCommitOutcome: TurnCommitOutcome? = null,
)
