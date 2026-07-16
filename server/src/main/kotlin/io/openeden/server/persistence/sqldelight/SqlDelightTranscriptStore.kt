package io.openeden.server.persistence.sqldelight

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.openeden.server.db.Database
import io.openeden.transcript.ActiveIncarnation
import io.openeden.transcript.ConversationHistoryPage
import io.openeden.transcript.ConversationTurn
import io.openeden.transcript.HistoryCursor
import io.openeden.transcript.InvalidHistoryCursorException
import io.openeden.transcript.TranscriptStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.channels.FileChannel
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class SqlDelightTranscriptStore private constructor(
    private val database: Database,
    private val driver: SqlDriver,
    private val ioDispatcher: CoroutineDispatcher,
) : TranscriptStore {
    private val queries get() = database.transcriptQueries

    override suspend fun activeIncarnation(): ActiveIncarnation = withContext(ioDispatcher) {
        queries.selectActiveIncarnation(::ActiveIncarnation).executeAsOne()
    }

    override suspend fun append(turn: ConversationTurn) = withContext(ioDispatcher) {
        val active = queries.selectActiveIncarnation(::ActiveIncarnation).executeAsOne()
        require(turn.incarnationId == active.id) {
            "Turn incarnation '${turn.incarnationId}' does not match active incarnation '${active.id}'"
        }

        queries.insertTurnIfAbsent(
            turn_id = turn.turnId,
            incarnation_id = turn.incarnationId,
            session_id = turn.sessionId,
            platform = turn.platform,
            scope_id = turn.scopeId,
            user_id = turn.userId,
            user_text = turn.userText,
            assistant_text = turn.assistantText,
            completed_at_ms = turn.completedAtMs,
        )
        val persisted = queries.selectTurnById(turn.turnId, ::mapTurn).executeAsOne()
        require(persisted == turn) {
            "Turn ID '${turn.turnId}' already exists with a different payload"
        }
    }

    override suspend fun page(
        limit: Int,
        before: HistoryCursor?,
    ): ConversationHistoryPage = withContext(ioDispatcher) {
        val active = queries.selectActiveIncarnation(::ActiveIncarnation).executeAsOne()
        if (before != null && before.incarnationId != active.id) {
            throw InvalidHistoryCursorException(
                "Cursor incarnation '${before.incarnationId}' does not match active incarnation '${active.id}'",
            )
        }

        val clampedLimit = limit.coerceIn(MIN_PAGE_SIZE, MAX_PAGE_SIZE)
        val candidates = queries.selectTurnPage(
            incarnationId = active.id,
            beforeCompletedAtMs = before?.completedAtMs,
            beforeTurnId = before?.turnId.orEmpty(),
            pageLimit = (clampedLimit + 1).toLong(),
            mapper = ::mapTurn,
        ).executeAsList()
        val hasMore = candidates.size > clampedLimit
        val turns = candidates.take(clampedLimit).asReversed()

        ConversationHistoryPage(
            turns = turns,
            before = if (hasMore) turns.first().toCursor(active.id) else null,
            hasMore = hasMore,
        )
    }

    suspend fun close() = withContext(ioDispatcher) {
        driver.close()
    }

    private fun ConversationTurn.toCursor(incarnationId: String) = HistoryCursor(
        incarnationId = incarnationId,
        completedAtMs = completedAtMs,
        turnId = turnId,
    )

    companion object {
        private const val MIN_PAGE_SIZE = 1
        private const val MAX_PAGE_SIZE = 50

        suspend fun open(
            dbPath: Path,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): SqlDelightTranscriptStore = withContext(ioDispatcher) {
            val resolvedPath = dbPath.resolveForInitialization()
            initializationLocks.computeIfAbsent(resolvedPath) { Mutex() }.withLock {
                FileChannel.open(
                    resolvedPath.resolveSibling("${resolvedPath.fileName}.init.lock"),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                ).use { channel ->
                    channel.lock().use {
                        val driver = JdbcSqliteDriver(
                            "jdbc:sqlite:$resolvedPath",
                            Properties(),
                            Database.Schema,
                        )
                        val database = Database(driver)
                        database.transcriptQueries.insertIncarnationIfAbsent(
                            active_incarnation_id = UUID.randomUUID().toString(),
                            created_at_ms = System.currentTimeMillis(),
                        )
                        SqlDelightTranscriptStore(database, driver, ioDispatcher)
                    }
                }
            }
        }

        private val initializationLocks = ConcurrentHashMap<Path, Mutex>()

        private fun Path.resolveForInitialization(): Path {
            val absolute = toAbsolutePath().normalize()
            val parent = absolute.parent ?: return absolute
            Files.createDirectories(parent)
            return if (Files.exists(absolute)) {
                absolute.toRealPath()
            } else {
                parent.toRealPath().resolve(absolute.fileName)
            }
        }

        private fun mapTurn(
            turnId: String,
            incarnationId: String,
            sessionId: String,
            platform: String,
            scopeId: String,
            userId: String,
            userText: String,
            assistantText: String,
            completedAtMs: Long,
        ) = ConversationTurn(
            turnId = turnId,
            incarnationId = incarnationId,
            sessionId = sessionId,
            platform = platform,
            scopeId = scopeId,
            userId = userId,
            userText = userText,
            assistantText = assistantText,
            completedAtMs = completedAtMs,
        )
    }
}
