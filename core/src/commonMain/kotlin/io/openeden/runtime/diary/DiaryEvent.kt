package io.openeden.runtime.diary

data class DiaryEvent(
    val sessionId: String,
    val traceId: String,
    val reason: String,
)
