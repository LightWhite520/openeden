package io.openeden.runtime.state

import io.openeden.transcript.TurnCommitOutcome

data class VectorWriteResult<T>(
    val state: T,
    val traceTags: Set<String>,
    val turnCommitOutcome: TurnCommitOutcome? = null,
)
