package io.openeden.server.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.openeden.runtime.DiaryTask
import io.openeden.runtime.DiaryTaskStatus
import io.openeden.runtime.DiaryTaskStore
import io.openeden.runtime.DiaryCheckpoint
import io.openeden.runtime.DiaryCheckpointStore
import io.openeden.trace.TraceTag
import kotlin.math.min
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

class SqlDelightDiaryTaskStore(
    private val database: Database,
    private val driver: SqlDriver? = null,
) : DiaryTaskStore, DiaryCheckpointStore {
    private val queries get() = database.memoryQueries

    override suspend fun enqueue(task: DiaryTask): Set<String> {
        return database.transactionWithResult {
            val active = queries.countActiveDiaryTasks(task.sessionId).executeAsOne()
            if (active >= 8L) return@transactionWithResult setOf(TraceTag.DiaryQueueOverflow)
            insert(task, replace = false)
            emptySet()
        }
    }

    override suspend fun enqueueIfAbsent(task: DiaryTask): Set<String> {
        return database.transactionWithResult {
            if (queries.selectDiaryTask(task.id, ::map).executeAsOneOrNull() != null) return@transactionWithResult emptySet()
            val active = queries.countActiveDiaryTasks(task.sessionId).executeAsOne()
            if (active >= 8L) return@transactionWithResult setOf(TraceTag.DiaryQueueOverflow)
            insert(task, replace = true)
            emptySet()
        }
    }

    override suspend fun leaseNext(sessionId: String, nowMs: Long, leaseMs: Long): DiaryTask? {
        val candidate = queries.selectPendingDiaryTask(sessionId, nowMs, ::map).executeAsOneOrNull() ?: return null
        queries.markDiaryTaskRunning(nowMs + leaseMs, candidate.id)
        return queries.selectDiaryTask(candidate.id, ::map).executeAsOneOrNull()
    }

    override suspend fun complete(taskId: String) {
        queries.completeDiaryTask(taskId)
    }

    override suspend fun completeWithCheckpoint(taskId: String, checkpoint: DiaryCheckpoint) {
        database.transaction {
            queries.completeDiaryTask(taskId)
            queries.upsertDiaryCheckpoint(
                checkpointSession(taskId), checkpoint.lastCoveredRawMemoryId,
                checkpoint.lastSuccessfulDiaryAtMs, checkpoint.lastNarrativeMemoryId,
            )
        }
    }

    override suspend fun read(sessionId: String): DiaryCheckpoint? =
        queries.selectDiaryCheckpoint(sessionId) { covered, at, narrative -> DiaryCheckpoint(covered, at, narrative) }
            .executeAsOneOrNull()

    suspend fun readCheckpoint(sessionId: String): DiaryCheckpoint? = read(sessionId)

    override suspend fun sessions(): Set<String> = queries.selectDiaryCheckpointSessions().executeAsList().toSet()

    fun countActive(sessionId: String): Long = queries.countActiveDiaryTasks(sessionId).executeAsOne()

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

    private fun insert(task: DiaryTask, replace: Boolean) {
        if (replace) queries.insertDiaryTaskIfAbsent(
            task.id, task.sessionId, task.sourceMemoryId, task.reason, task.status.name,
            task.attempts.toLong(), task.id.substringAfterLast(':', "0").toLongOrNull() ?: 0L,
            task.availableAtMs, task.leaseExpiresAtMs, task.lastError,
        ) else queries.insertDiaryTask(
            task.id, task.sessionId, task.sourceMemoryId, task.reason, task.status.name,
            task.attempts.toLong(), task.id.substringAfterLast(':', "0").toLongOrNull() ?: 0L,
            task.availableAtMs, task.leaseExpiresAtMs, task.lastError,
        )
    }

    private fun checkpointSession(taskId: String): String =
        queries.selectDiaryTask(taskId, ::map).executeAsOne().sessionId

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
