package io.openeden.runtime

data class DiaryTask(
    val id: String,
    val sessionId: String,
    val sourceMemoryId: String?,
    val reason: String,
    val status: DiaryTaskStatus = DiaryTaskStatus.PENDING,
    val attempts: Int = 0,
    val availableAtMs: Long = 0L,
    val leaseExpiresAtMs: Long? = null,
    val lastError: String? = null,
)
