package io.openeden.runtime.diary

data class DiaryTask(
    val id: String,
    val sessionId: String,
    val sourceMemoryId: String?,
    val reason: String,
    val status: DiaryTaskStatus = DiaryTaskStatus.PENDING,
    val attempts: Int = 0,
    val availableAtMs: Long = 0L,
    val leaseExpiresAtMs: Long? = null,
    val leaseToken: String? = null,
    val lastError: String? = null,
    val incarnationId: String = "",
    val sourceSessionId: String = "",
    val platform: String = "",
    val userId: String = "",
    val canonicalSubjectId: String = "",
    val visibility: io.openeden.memory.MemoryVisibility = io.openeden.memory.MemoryVisibility.ScopeShared(""),
)
