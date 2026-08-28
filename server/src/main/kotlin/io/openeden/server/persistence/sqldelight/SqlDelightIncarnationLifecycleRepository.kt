package io.openeden.server.persistence.sqldelight

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.openeden.archive.ArchivedDiaryEntry
import io.openeden.archive.DiaryArchivePage
import io.openeden.archive.DiaryArchiveReader
import io.openeden.runtime.lifecycle.IncarnationLifecycle
import io.openeden.runtime.lifecycle.IncarnationTerminationStore
import io.openeden.runtime.lifecycle.TerminationReason
import io.openeden.server.db.Database
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

class DiaryArchiveVerificationException(message: String) : IllegalStateException(message)

class SqlDelightIncarnationLifecycleRepository(
    private val database: Database,
    private val driver: SqlDriver,
    private val ioDispatcher: CoroutineDispatcher = newSqliteDispatcher("openeden-lifecycle-sqlite"),
) : IncarnationTerminationStore, DiaryArchiveReader {
    override suspend fun read(): IncarnationLifecycle = withContext(ioDispatcher) {
        current().status
    }

    suspend fun activeIncarnationId(): String = withContext(ioDispatcher) { current().id }

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
            database.incarnationQueries.updateFreshIncarnation(freshId, nowMs, nowMs, requestId)
        }
        freshId
    }

    override suspend fun archiveAndPurge(reason: TerminationReason) = withContext(ioDispatcher) {
        database.transaction {
            val lifecycle = current()
            check(lifecycle.status == IncarnationLifecycle.TERMINATING) {
                "Archive purge requires TERMINATING lifecycle"
            }
            val narratives = database.memoryQueries.selectNarrativeMemories { id, content, createdAtMs ->
                NarrativeRow(id, content, createdAtMs)
            }.executeAsList()
            val archiveQueries = database.diaryArchiveQueries
            narratives.forEach { diary ->
                archiveQueries.insertArchive(
                    archive_entry_id = "${lifecycle.id}:${diary.id}",
                    incarnation_id = lifecycle.id,
                    source_diary_id = diary.id,
                    content = diary.content,
                    original_created_at_ms = diary.createdAtMs,
                    archived_at_ms = reason.requestedAtMs,
                    archive_reason = reason.code,
                    content_sha256 = sha256(diary.content),
                )
            }
            verifyArchives(lifecycle.id, narratives)
            database.memoryQueries.deleteAllMemoryEmbeddings()
            database.memoryQueries.deleteAllMemoryEntries()
            database.memoryQueries.deleteAllDiaryTasks()
            database.memoryQueries.deleteAllDiaryCheckpoints()
            database.memoryQueries.deleteAllTraceSpans()
            database.sessionStateQueries.deleteAllSessionStates()
            database.relationshipQueries.deleteAllRelationshipEvents()
            database.relationshipQueries.deleteAllRelationships()
            database.transcriptQueries.deleteAllConversationTurns()
            database.incarnationQueries.updateLifecycle(
                lifecycle_status = IncarnationLifecycle.TERMINATED.name,
                lifecycle_changed_at_ms = reason.requestedAtMs,
                termination_reason = reason.code,
                lifecycle_request_id = lifecycle.requestId,
            )
        }
    }

    override suspend fun page(
        incarnationId: String,
        limit: Int,
        before: String?,
    ): DiaryArchivePage = withContext(ioDispatcher) {
        val clampedLimit = limit.coerceIn(MIN_PAGE_SIZE, MAX_PAGE_SIZE)
        val candidates = database.diaryArchiveQueries.selectArchivePage(
            incarnationId = incarnationId,
            beforeArchiveEntryId = before,
            pageLimit = (clampedLimit + 1).toLong(),
            mapper = ::mapArchive,
        ).executeAsList()
        val hasMore = candidates.size > clampedLimit
        val entries = candidates.take(clampedLimit)
        DiaryArchivePage(
            entries = entries,
            before = entries.lastOrNull()?.archiveEntryId.takeIf { hasMore },
            hasMore = hasMore,
        )
    }

    suspend fun close() = withContext(ioDispatcher) {
        if (driver is JdbcSqliteDriver) driver.closeCurrentThreadConnection()
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
            database.incarnationQueries.updateLifecycle(
                lifecycle_status = next.name,
                lifecycle_changed_at_ms = System.currentTimeMillis(),
                termination_reason = null,
                lifecycle_request_id = current.requestId,
            )
        }
        result
    }

    private fun verifyArchives(incarnationId: String, narratives: List<NarrativeRow>) {
        val archiveQueries = database.diaryArchiveQueries
        val actualCount = archiveQueries.countArchive(incarnationId).executeAsOne()
        if (actualCount != narratives.size.toLong()) {
            throw DiaryArchiveVerificationException(
                "Expected ${narratives.size} archived diary rows, found $actualCount",
            )
        }
        narratives.forEach { diary ->
            val archived = archiveQueries.selectArchiveBySource(
                incarnation_id = incarnationId,
                source_diary_id = diary.id,
                mapper = ::mapArchive,
            ).executeAsOneOrNull()
                ?: throw DiaryArchiveVerificationException("Missing archive row for ${diary.id}")
            if (archived.content != diary.content || archived.contentSha256 != sha256(diary.content)) {
                throw DiaryArchiveVerificationException("Archive verification failed for ${diary.id}")
            }
        }
    }

    private fun current(): LifecycleRow = database.incarnationQueries.selectLifecycle { id, createdAt, status, changedAt, reason, requestId ->
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

    private fun mapArchive(
        archiveEntryId: String,
        incarnationId: String,
        sourceDiaryId: String,
        content: String,
        originalCreatedAtMs: Long,
        archivedAtMs: Long,
        archiveReason: String,
        contentSha256: String,
    ) = ArchivedDiaryEntry(
        archiveEntryId = archiveEntryId,
        incarnationId = incarnationId,
        sourceDiaryId = sourceDiaryId,
        content = content,
        originalCreatedAtMs = originalCreatedAtMs,
        archivedAtMs = archivedAtMs,
        archiveReason = archiveReason,
        contentSha256 = contentSha256,
    )

    private data class LifecycleRow(
        val id: String,
        val createdAtMs: Long,
        val status: IncarnationLifecycle,
        val changedAtMs: Long,
        val reason: String?,
        val requestId: String?,
    )

    private data class NarrativeRow(
        val id: String,
        val content: String,
        val createdAtMs: Long,
    )

    companion object {
        private const val MIN_PAGE_SIZE = 1
        private const val MAX_PAGE_SIZE = 50

        fun open(
            dbPath: Path,
            ioDispatcher: CoroutineDispatcher = newSqliteDispatcher("openeden-lifecycle-sqlite"),
        ): SqlDelightIncarnationLifecycleRepository {
            dbPath.parent?.let { Files.createDirectories(it) }
            val driver = JdbcSqliteDriver("jdbc:sqlite:${dbPath.toAbsolutePath()}", Properties(), Database.Schema)
            driver.closeCurrentThreadConnection()
            return SqlDelightIncarnationLifecycleRepository(Database(driver), driver, ioDispatcher)
        }

        private fun JdbcSqliteDriver.closeCurrentThreadConnection() {
            closeConnection(getConnection())
        }

        private fun sha256(content: String): String = MessageDigest.getInstance("SHA-256")
            .digest(content.encodeToByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}
