package io.openeden.server.persistence.sqldelight

import io.openeden.server.db.Database
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.openeden.runtime.diary.DiaryTask
import io.openeden.runtime.diary.DiaryTaskStatus
import io.openeden.runtime.diary.DiaryTaskStore
import io.openeden.runtime.diary.DiaryCheckpoint
import io.openeden.runtime.diary.DiaryCheckpointStore
import io.openeden.memory.MemoryVisibility
import io.openeden.trace.TraceTag
import kotlin.math.min
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.sql.SQLException
import java.util.UUID

class SqlDelightDiaryTaskStore(
    private val database: Database,
    private val driver: SqlDriver? = null,
) : DiaryTaskStore, DiaryCheckpointStore {
    private val queries get() = database.memoryQueries

    override suspend fun enqueue(task: DiaryTask): Set<String> {
        return withContext(Dispatchers.IO) { retry { database.transactionWithResult {
            val active = queries.countActiveDiaryTasks(task.sessionId).executeAsOne()
            if (active >= 8L) return@transactionWithResult setOf(TraceTag.DiaryQueueOverflow)
            insert(task, replace = false)
            emptySet()
        } } }
    }

    override suspend fun enqueueIfAbsent(task: DiaryTask): Set<String> {
        return withContext(Dispatchers.IO) { retry { database.transactionWithResult {
            if (queries.selectDiaryTask(task.id, ::map).executeAsOneOrNull() != null) return@transactionWithResult emptySet()
            val active = queries.countActiveDiaryTasks(task.sessionId).executeAsOne()
            if (active >= 8L) return@transactionWithResult setOf(TraceTag.DiaryQueueOverflow)
            insert(task, replace = true)
            emptySet()
        } } }
    }

    override suspend fun leaseNext(sessionId: String, nowMs: Long, leaseMs: Long): DiaryTask? {
        return withContext(Dispatchers.IO) {
            retry { database.transactionWithResult {
                val candidate = queries.selectPendingDiaryTask(sessionId, nowMs, ::map).executeAsOneOrNull() ?: return@transactionWithResult null
                val expires = nowMs + leaseMs
                val token = UUID.randomUUID().toString()
                queries.markDiaryTaskRunning(expires, token, candidate.id)
                if (queries.selectChanges().executeAsOne() == 0L) return@transactionWithResult null
                queries.selectDiaryTask(candidate.id, ::map).executeAsOneOrNull()
                    ?.takeIf { it.status == DiaryTaskStatus.RUNNING && it.leaseExpiresAtMs == expires && it.leaseToken == token }
            } }
        }
    }

    override suspend fun complete(taskId: String) { error("Lease token required") }
    override suspend fun complete(taskId: String, leaseToken: String) {
        withContext(Dispatchers.IO) { queries.completeDiaryTask(taskId, leaseToken) }
    }

    override suspend fun completeWithCheckpoint(taskId: String, leaseToken: String, checkpoint: DiaryCheckpoint) {
        withContext(Dispatchers.IO) { database.transaction {
            queries.completeDiaryTask(taskId, leaseToken)
            if (queries.selectChanges().executeAsOne() == 0L) return@transaction
            queries.upsertDiaryCheckpoint(
                checkpointSession(taskId), checkpoint.lastCoveredRawMemoryId,
                checkpoint.lastSuccessfulDiaryAtMs, checkpoint.lastNarrativeMemoryId,
            )
        } }
    }

    override suspend fun completeWithCheckpointIfOwned(taskId: String, leaseToken: String, checkpoint: DiaryCheckpoint): Boolean =
        withContext(Dispatchers.IO) { database.transactionWithResult {
            queries.completeDiaryTask(taskId, leaseToken)
            if (queries.selectChanges().executeAsOne() != 1L) return@transactionWithResult false
            queries.upsertDiaryCheckpoint(checkpointSession(taskId), checkpoint.lastCoveredRawMemoryId, checkpoint.lastSuccessfulDiaryAtMs, checkpoint.lastNarrativeMemoryId)
            true
        } }

    override suspend fun read(sessionId: String): DiaryCheckpoint? = withContext(Dispatchers.IO) {
        queries.selectDiaryCheckpoint(sessionId) { covered, at, narrative -> DiaryCheckpoint(covered, at, narrative) }.executeAsOneOrNull()
    }

    suspend fun readCheckpoint(sessionId: String): DiaryCheckpoint? = read(sessionId)

    override suspend fun sessions(): Set<String> = withContext(Dispatchers.IO) { queries.selectDiaryCheckpointSessions().executeAsList().toSet() }

    suspend fun countActive(sessionId: String): Long = withContext(Dispatchers.IO) { queries.countActiveDiaryTasks(sessionId).executeAsOne() }

    override suspend fun fail(taskId: String, nowMs: Long, error: String, maxAttempts: Int) {
        withContext(Dispatchers.IO) {
        val task = queries.selectDiaryTask(taskId, ::map).executeAsOneOrNull() ?: return@withContext
        val attempts = task.attempts + 1
        val status = if (attempts >= maxAttempts) DiaryTaskStatus.DEAD else DiaryTaskStatus.PENDING
        val delay = 1000L * (1L shl min(attempts, 10))
        queries.failDiaryTask(status.name, attempts.toLong(), nowMs + delay, error.take(500), taskId)
        }
    }

    override suspend fun recoverExpired(nowMs: Long) {
        withContext(Dispatchers.IO) { queries.recoverExpiredDiaryTasks(nowMs) }
    }

    suspend fun readById(id: String): DiaryTask? = withContext(Dispatchers.IO) { queries.selectDiaryTask(id, ::map).executeAsOneOrNull() }

    fun close() = driver?.close()

    private fun insert(task: DiaryTask, replace: Boolean) {
        val persisted = normalize(task)
        if (replace) queries.insertDiaryTaskIfAbsent(
            persisted.id, persisted.sessionId, persisted.sourceMemoryId, persisted.reason, persisted.status.name,
            persisted.attempts.toLong(), createdAtMsFromId(persisted.id), persisted.availableAtMs, persisted.leaseExpiresAtMs, persisted.leaseToken, persisted.lastError,
            persisted.incarnationId, persisted.sourceSessionId, persisted.platform, persisted.userId, persisted.canonicalSubjectId,
            persisted.visibility.persistenceKind(), persisted.visibility.persistenceSubjectId(), persisted.visibility.persistenceSessionId(),
        ) else queries.insertDiaryTask(
            persisted.id, persisted.sessionId, persisted.sourceMemoryId, persisted.reason, persisted.status.name,
            persisted.attempts.toLong(), createdAtMsFromId(persisted.id), persisted.availableAtMs, persisted.leaseExpiresAtMs, persisted.leaseToken, persisted.lastError,
            persisted.incarnationId, persisted.sourceSessionId, persisted.platform, persisted.userId, persisted.canonicalSubjectId,
            persisted.visibility.persistenceKind(), persisted.visibility.persistenceSubjectId(), persisted.visibility.persistenceSessionId(),
        )
    }

    override suspend fun fail(taskId: String, leaseToken: String, nowMs: Long, error: String, maxAttempts: Int) {
        withContext(Dispatchers.IO) {
            val task = queries.selectDiaryTask(taskId, ::map).executeAsOneOrNull() ?: return@withContext
            val attempts = task.attempts + 1
            val status = if (attempts >= maxAttempts) DiaryTaskStatus.DEAD else DiaryTaskStatus.PENDING
            val delay = 1000L * (1L shl min(attempts, 10))
            queries.failDiaryTaskOwned(status.name, attempts.toLong(), nowMs + delay, error.take(500), taskId, leaseToken)
        }
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
        leaseToken: String?,
        lastError: String?,
        incarnationId: String,
        sourceSessionId: String,
        platform: String,
        diaryUserId: String,
        canonicalSubjectId: String,
        visibilityKind: String,
        visibilitySubjectId: String?,
        visibilitySessionId: String?,
    ): DiaryTask = DiaryTask(
        id = id,
        sessionId = sessionId,
        sourceMemoryId = sourceMemoryId,
        reason = reason,
        status = DiaryTaskStatus.valueOf(status),
        attempts = attempts.toInt(),
        availableAtMs = availableAtMs,
        leaseExpiresAtMs = leaseExpiresAtMs,
        leaseToken = leaseToken,
        lastError = lastError,
        incarnationId = incarnationId,
        sourceSessionId = sourceSessionId,
        platform = platform,
        userId = diaryUserId,
        canonicalSubjectId = canonicalSubjectId,
        visibility = memoryVisibilityFromPersistence(visibilityKind, visibilitySubjectId, visibilitySessionId),
    )

    private fun normalize(task: DiaryTask): DiaryTask {
        val sourceSessionId = task.sourceSessionId.ifBlank { task.sessionId }
        val legacySubjectId = "legacy:diary:$sourceSessionId"
        val canonicalSubjectId = task.canonicalSubjectId.ifBlank { legacySubjectId }
        val visibility = if (task.canonicalSubjectId.isBlank()) MemoryVisibility.OperatorOnly else task.visibility
        return task.copy(
            incarnationId = task.incarnationId.ifBlank { activeIncarnationId() },
            sourceSessionId = sourceSessionId,
            platform = task.platform.ifBlank { sourceSessionId.substringBefore(':', sourceSessionId) },
            canonicalSubjectId = canonicalSubjectId,
            visibility = visibility.normalize(sourceSessionId, canonicalSubjectId),
        )
    }

    private fun activeIncarnationId(): String = database.transcriptQueries
        .selectActiveIncarnation { incarnationId, _ -> incarnationId }
        .executeAsOneOrNull()
        ?: LEGACY_INCARNATION_ID

    private suspend fun <T> retry(block: () -> T): T {
        repeat(6) { attempt ->
            try { return block() } catch (e: SQLException) {
                if (!e.message.orEmpty().contains("locked", ignoreCase = true) || attempt == 5) throw e
                delay(25L * (attempt + 1))
            }
        }
        error("unreachable")
    }

    companion object {
        private const val LEGACY_INCARNATION_ID = "legacy-incarnation"
        fun open(dbPath: Path): SqlDelightDiaryTaskStore {
            dbPath.parent?.let { Files.createDirectories(it) }
            val driver = JdbcSqliteDriver("jdbc:sqlite:${dbPath.toAbsolutePath()}", Properties(), Database.Schema)
            return SqlDelightDiaryTaskStore(Database(driver), driver)
        }
    }
}

private fun MemoryVisibility.normalize(
    sourceSessionId: String,
    canonicalSubjectId: String,
): MemoryVisibility = when (this) {
    is MemoryVisibility.PrivateSubject -> MemoryVisibility.PrivateSubject(subjectId.ifBlank { canonicalSubjectId })
    is MemoryVisibility.ScopeShared -> MemoryVisibility.ScopeShared(sessionId.ifBlank { sourceSessionId })
    MemoryVisibility.IncarnationShared -> MemoryVisibility.IncarnationShared
    MemoryVisibility.OperatorOnly -> MemoryVisibility.OperatorOnly
}

private fun MemoryVisibility.persistenceKind(): String = when (this) {
    is MemoryVisibility.PrivateSubject -> "PRIVATE_SUBJECT"
    is MemoryVisibility.ScopeShared -> "SCOPE_SHARED"
    MemoryVisibility.IncarnationShared -> "INCARNATION_SHARED"
    MemoryVisibility.OperatorOnly -> "OPERATOR_ONLY"
}

private fun MemoryVisibility.persistenceSubjectId(): String? = when (this) {
    is MemoryVisibility.PrivateSubject -> subjectId
    else -> null
}

private fun MemoryVisibility.persistenceSessionId(): String? = when (this) {
    is MemoryVisibility.ScopeShared -> sessionId
    else -> null
}

private fun memoryVisibilityFromPersistence(
    kind: String,
    subjectId: String?,
    sessionId: String?,
): MemoryVisibility = when (kind) {
    "PRIVATE_SUBJECT" -> MemoryVisibility.PrivateSubject(subjectId.orEmpty())
    "SCOPE_SHARED" -> MemoryVisibility.ScopeShared(sessionId.orEmpty())
    "INCARNATION_SHARED" -> MemoryVisibility.IncarnationShared
    "OPERATOR_ONLY" -> MemoryVisibility.OperatorOnly
    else -> error("Unsupported persisted memory visibility: $kind")
}
