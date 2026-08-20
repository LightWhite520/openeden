package io.openeden.server.persistence.sqldelight

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.openeden.server.db.Database
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.math.min

class MemoryVectorProjectionStore(
    private val database: Database,
    private val driver: SqlDriver? = null,
) : AutoCloseable {
    enum class ProjectionStatus { PENDING, RUNNING, READY }

    data class ProjectionWork(
        val memoryId: String,
        val modelId: String,
        val status: ProjectionStatus,
        val attempts: Int,
        val availableAtMs: Long,
        val lastError: String?,
        val updatedAtMs: Long,
    )

    private val queries get() = database.memoryQueries

    suspend fun enqueue(memoryId: String, modelId: String, nowMs: Long) = withContext(Dispatchers.IO) {
        requireId(memoryId)
        requireId(modelId)
        requireTimestamp(nowMs)
        queries.upsertVectorSync(memoryId, modelId, ProjectionStatus.PENDING.name, 0, nowMs, null, nowMs)
    }

    suspend fun read(memoryId: String): ProjectionWork? = withContext(Dispatchers.IO) {
        requireId(memoryId)
        queries.selectVectorSync(memoryId, ::map).executeAsOneOrNull()
    }

    suspend fun claimDue(nowMs: Long, batchSize: Int): List<ProjectionWork> = withContext(Dispatchers.IO) {
        requireTimestamp(nowMs)
        require(batchSize > 0) { "batchSize must be positive" }
        database.transactionWithResult {
            queries.selectDueVectorSync(nowMs, batchSize.toLong(), ::map).executeAsList().mapNotNull { candidate ->
                queries.claimVectorSync(nowMs, candidate.memoryId)
                if (queries.selectChanges().executeAsOne() != 1L) return@mapNotNull null
                queries.selectVectorSync(candidate.memoryId, ::map).executeAsOneOrNull()
            }
        }
    }

    suspend fun markReady(memoryId: String, nowMs: Long) = withContext(Dispatchers.IO) {
        requireId(memoryId)
        requireTimestamp(nowMs)
        queries.markVectorSyncReady(nowMs, memoryId)
    }

    suspend fun markReady(memoryIds: Collection<String>, nowMs: Long) = withContext(Dispatchers.IO) {
        requireTimestamp(nowMs)
        memoryIds.forEach(::requireId)
        database.transaction { memoryIds.forEach { queries.markVectorSyncReady(nowMs, it) } }
    }

    suspend fun reschedule(memoryId: String, nowMs: Long, error: String?) = withContext(Dispatchers.IO) {
        requireId(memoryId)
        requireTimestamp(nowMs)
        val existing = queries.selectVectorSync(memoryId, ::map).executeAsOneOrNull() ?: return@withContext
        val attempts = existing.attempts + 1
        val delayMs = min(300_000L, 1_000L * (1L shl min(attempts, 8)))
        queries.rescheduleVectorSync(attempts.toLong(), nowMs + delayMs, sanitizeError(error), nowMs, memoryId)
    }

    suspend fun recoverRunning(nowMs: Long) = withContext(Dispatchers.IO) {
        requireTimestamp(nowMs)
        queries.recoverRunningVectorSync(nowMs, nowMs)
    }

    suspend fun pendingCount(): Long = withContext(Dispatchers.IO) {
        queries.countPendingVectorSync().executeAsOne()
    }

    suspend fun selectModelRefresh(activeModelId: String, batchSize: Int): List<ProjectionWork> = withContext(Dispatchers.IO) {
        requireId(activeModelId)
        require(batchSize > 0) { "batchSize must be positive" }
        queries.selectVectorSyncForModelRefresh(activeModelId, batchSize.toLong(), ::map).executeAsList()
    }

    override fun close() { driver?.close() }

    private fun map(
        memoryId: String,
        modelId: String,
        status: String,
        attempts: Long,
        availableAtMs: Long,
        lastError: String?,
        updatedAtMs: Long,
    ) = ProjectionWork(
        memoryId,
        modelId,
        ProjectionStatus.valueOf(status),
        attempts.toInt(),
        availableAtMs,
        lastError,
        updatedAtMs,
    )

    private fun sanitizeError(error: String?): String? = error?.replace(Regex("\\s+"), " ")?.trim()?.take(500)?.ifEmpty { null }

    private fun requireId(value: String) { require(value.isNotBlank()) { "id must not be blank" } }
    private fun requireTimestamp(value: Long) { require(value >= 0) { "timestamp must be non-negative" } }

    companion object {
        fun open(dbPath: Path): MemoryVectorProjectionStore {
            dbPath.parent?.let(Files::createDirectories)
            val driver = JdbcSqliteDriver("jdbc:sqlite:${dbPath.toAbsolutePath()}", Properties(), Database.Schema)
            val store = MemoryVectorProjectionStore(Database(driver), driver)
            runBlocking(Dispatchers.IO) { store.recoverRunning(0L) }
            return store
        }
    }
}
