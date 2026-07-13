package io.openeden.runtime

data class VectorWriteResult(
    val state: SessionState,
    val traceTags: Set<String>,
)
