package io.openeden.runtime

data class DiaryEvent(
    val sessionId: String,
    val traceId: String,
    val reason: String,
)
