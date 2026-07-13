package io.openeden.server.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.openeden.runtime.DiaryTask
import io.openeden.runtime.DiaryTaskStatus
import io.openeden.runtime.DiaryTaskStore
import io.openeden.trace.TraceTag
import kotlin.math.min
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

class SqlDelightDiaryTaskStore(
    private val database: Database,
    private val driver: SqlDriver? = null,
) : DiaryTaskStore {
    private val queries get() = database.memoryQueries

    override suspend fun enqueue(task: DiaryTask): Set<String> {
        val active = queries.countActiveDiaryTasks(task.sessionId).executeAsOne()
        if (active >= 8L) return setOf(TraceTag.DiaryQueueOverflow)
        queries.insertDiaryTask(
            id = task.id,
            session_id = task.sessionId,
            source_memory_id = task.sourceMemoryId,
            reason = task.reason,
            status = task.status.name,
            attempts = task.attempts.toLong(),
            created_at_ms = task.id.substringAfterLast(':', "0").toLongOrNull() ?: 0L,
            available_at_ms = task.availableAtMs,
            lease_expires_at_ms = task.leaseExpiresAtMs,
            last_error = task.lastError,
        )
        return emptySet()
    }

    override suspend fun enqueueIfAbsent(task: DiaryTask): Set<String> {
        if (queries.selectDiaryTask(task.id, ::map).executeAsOneOrNull() != null) return emptySet()
        val active = queries.countActiveDiaryTasks(task.sessionId).executeAsOne()
        if (active >= 8L) return setOf(TraceTag.DiaryQueueOverflow)
        queries.insertDiaryTaskIfAbsent(
            id = task.id,
            session_id = task.sessionId,
            source_memory_id = task.sourceMemoryId,
            reason = task.reason,
            status = task.status.name,
            attempts = task.attempts.toLong(),
            created_at_ms = task.id.substringAfterLast(':', "0").toLongOrNull() ?: 0L,
            available_at_ms = task.availableAtMs,
            lease_expires_at_ms = task.leaseExpiresAtMs,
            last_error = task.lastError,
        )
        return emptySet()
    }

    override suspend fun leaseNext(sessionId: String, nowMs: Long, leaseMs: Long): DiaryTask? {
        val candidate = queries.selectPendingDiaryTask(sessionId, nowMs, ::map).executeAsOneOrNull() ?: return null
        queries.markDiaryTaskRunning(nowMs + leaseMs, candidate.id)
        return queries.selectDiaryTask(candidate.id, ::map).executeAsOneOrNull()
    }

    override suspend fun complete(taskId: String) {
        queries.completeDiaryTask(taskId)
    }

    override suspend fun fail(taskId: String, nowMs: Long, error: String, maxAttempts: Int) {
        val task = queries.selectDiaryTask(taskId, ::map).executeAsOneOrNull() ?: return
        val attempts = task.attempts + 1
        val status = if (attempts >= maxAttempts) DiaryTaskStatus.DEAD else DiaryTaskStatus.PENDING
        val delay = 1000L * (1L shl min(attempts, 10))
        queries.failDiaryTask(status.name, attempts.toLong(), nowMs + delay, error.take(500), taskId)
    }

    override suspend fun recoverExpired(nowMs: Long) {
        queries.recoverExpiredDiaryTasks(nowMs)
    }

    fun readById(id: String): DiaryTask? = queries.selectDiaryTask(id, ::map).executeAsOneOrNull()

    fun close() = driver?.close()

    @Suppress("LongParameterList")
    private fun map(
        id: String,
        sessionId: String,
        sourceMemoryId: String?,
        reason: String,
        status: String,
        attempts: Long,
        availableAtMs: Long,
        leaseExpiresAtMs: Long?,
        lastError: String?,
    ): DiaryTask = DiaryTask(
        id = id,
        sessionId = sessionId,
        sourceMemoryId = sourceMemoryId,
        reason = reason,
        status = DiaryTaskStatus.valueOf(status),
        attempts = attempts.toInt(),
        availableAtMs = availableAtMs,
        leaseExpiresAtMs = leaseExpiresAtMs,
        lastError = lastError,
    )

    companion object {
        fun open(dbPath: Path): SqlDelightDiaryTaskStore {
            dbPath.parent?.let { Files.createDirectories(it) }
            val driver = JdbcSqliteDriver("jdbc:sqlite:${dbPath.toAbsolutePath()}", Properties(), Database.Schema)
            return SqlDelightDiaryTaskStore(Database(driver), driver)
        }
    }
}
