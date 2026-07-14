package io.openeden.runtime.state

import io.openeden.runtime.session.SessionState

data class VectorWriteResult(
    val state: SessionState,
    val traceTags: Set<String>,
)
