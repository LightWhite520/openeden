package io.openeden.server.persistence.sqldelight

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.openeden.runtime.lifecycle.IncarnationLifecycle
import io.openeden.runtime.lifecycle.IncarnationLifecycleStore
import io.openeden.server.db.Database
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.UUID

class SqlDelightIncarnationLifecycleStore(
    private val database: Database,
    private val driver: JdbcSqliteDriver,
    private val ioDispatcher: CoroutineDispatcher = newSqliteDispatcher("openeden-lifecycle-store-sqlite"),
) : IncarnationLifecycleStore {
    private val queries get() = database.incarnationQueries

    override suspend fun read(): IncarnationLifecycle = withContext(ioDispatcher) {
        current().status
    }

    suspend fun activeIncarnationId(): String = withContext(ioDispatcher) {
        current().id
    }

    override suspend fun markCritical(): IncarnationLifecycle = transition(
        expected = IncarnationLifecycle.ACTIVE,
        next = IncarnationLifecycle.CRITICAL,
    )

    override suspend fun beginTermination(): IncarnationLifecycle = transition(
        expected = IncarnationLifecycle.CRITICAL,
        next = IncarnationLifecycle.TERMINATING,
    )

    override suspend fun markTerminated(): IncarnationLifecycle = transition(
        expected = IncarnationLifecycle.TERMINATING,
        next = IncarnationLifecycle.TERMINATED,
    )

    override suspend fun createFresh(requestId: String, nowMs: Long): String = withContext(ioDispatcher) {
        var freshId = ""
        database.transaction {
            val current = current()
            if (current.status == IncarnationLifecycle.ACTIVE && current.requestId == requestId) {
                freshId = current.id
                return@transaction
            }
            check(current.status == IncarnationLifecycle.TERMINATED) {
                "Fresh incarnation requires TERMINATED lifecycle"
            }
            freshId = UUID.randomUUID().toString()
            queries.updateFreshIncarnation(freshId, nowMs, nowMs, requestId)
        }
        freshId
    }

    suspend fun close() = withContext(ioDispatcher) {
        driver.closeCurrentThreadConnection()
        driver.close()
        (ioDispatcher as? ExecutorCoroutineDispatcher)?.close()
    }

    private suspend fun transition(
        expected: IncarnationLifecycle,
        next: IncarnationLifecycle,
    ): IncarnationLifecycle = withContext(ioDispatcher) {
        var result = next
        database.transaction {
            val current = current()
            if (current.status == next) {
                result = next
                return@transaction
            }
            check(current.status == expected) {
                "Illegal incarnation lifecycle transition: ${current.status} -> $next"
            }
            queries.updateLifecycle(next.name, System.currentTimeMillis(), null, current.requestId)
            result = next
        }
        result
    }

    private fun current(): LifecycleRow = queries.selectLifecycle { id, createdAt, status, changedAt, reason, requestId ->
        LifecycleRow(
            id = id,
            createdAtMs = createdAt,
            status = status.toLifecycle(),
            changedAtMs = changedAt,
            reason = reason,
            requestId = requestId,
        )
    }.executeAsOne()

    private fun String.toLifecycle(): IncarnationLifecycle = runCatching {
        IncarnationLifecycle.valueOf(this)
    }.getOrElse { error("Unsupported persisted incarnation lifecycle: $this") }

    private data class LifecycleRow(
        val id: String,
        val createdAtMs: Long,
        val status: IncarnationLifecycle,
        val changedAtMs: Long,
        val reason: String?,
        val requestId: String?,
    )

    companion object {
        fun open(
            dbPath: Path,
            ioDispatcher: CoroutineDispatcher = newSqliteDispatcher("openeden-lifecycle-store-sqlite"),
        ): SqlDelightIncarnationLifecycleStore {
            dbPath.parent?.let { Files.createDirectories(it) }
            val driver = JdbcSqliteDriver("jdbc:sqlite:${dbPath.toAbsolutePath()}", Properties(), Database.Schema)
            val database = Database(driver)
            database.incarnationQueries.insertLifecycleIfAbsent(
                active_incarnation_id = UUID.randomUUID().toString(),
                created_at_ms = System.currentTimeMillis(),
                lifecycle_changed_at_ms = System.currentTimeMillis(),
            )
            driver.closeCurrentThreadConnection()
            return SqlDelightIncarnationLifecycleStore(database, driver, ioDispatcher)
        }

        private fun JdbcSqliteDriver.closeCurrentThreadConnection() {
            closeConnection(getConnection())
        }
    }
}
