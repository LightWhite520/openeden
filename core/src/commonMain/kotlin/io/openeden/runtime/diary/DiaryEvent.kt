package io.openeden.runtime.diary

data class DiaryEvent(
    val sessionId: String,
    val traceId: String,
    val reason: String,
    val incarnationId: String = "",
    val canonicalSubjectId: String = "",
    val visibility: io.openeden.memory.MemoryVisibility = io.openeden.memory.MemoryVisibility.ScopeShared(""),
)
