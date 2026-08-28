package io.openeden.server.persistence.sqldelight

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.openeden.server.db.Database
import io.openeden.transcript.ActiveIncarnation
import io.openeden.transcript.ConversationHistoryPage
import io.openeden.transcript.ConversationTurn
import io.openeden.transcript.HistoryCursor
import io.openeden.transcript.InvalidHistoryCursorException
import io.openeden.transcript.PromptHistoryAssembler
import io.openeden.transcript.PromptHistoryChunk
import io.openeden.transcript.PromptHistorySnapshot
import io.openeden.transcript.TranscriptStore
import io.openeden.transcript.TurnPostCommitPlan
import io.openeden.transcript.TurnPostCommitStage
import io.openeden.transcript.TurnPostCommitState
import io.openeden.relationship.RelationshipEvaluation
import io.openeden.runtime.time.RuntimeClock
import io.openeden.runtime.time.SystemRuntimeClock
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.channels.FileChannel
import java.util.Properties
import java.util.UUID

class SqlDelightTranscriptStore private constructor(
    private val database: Database,
    private val driver: SqlDriver,
    private val ioDispatcher: CoroutineDispatcher,
    private val promptHistoryAssembler: PromptHistoryAssembler,
    private val clock: RuntimeClock,
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

    override suspend fun recentForSession(sessionId: String, limit: Int): List<ConversationTurn> =
        withContext(ioDispatcher) {
            val requestedLimit = limit.coerceAtLeast(0)
            if (requestedLimit == 0) return@withContext emptyList()

            queries.selectRecentForSession(
                sessionId = sessionId,
                limit = requestedLimit.toLong(),
                mapper = ::mapTurn,
            ).executeAsList().asReversed()
        }

    override suspend fun findByTurnId(turnId: String): ConversationTurn? = withContext(ioDispatcher) {
        queries.selectTurnById(turnId, ::mapTurn).executeAsOneOrNull()
    }

    override suspend fun postCommitState(turnId: String): TurnPostCommitState? = withContext(ioDispatcher) {
        queries.selectTurnPostCommit(turnId) { planJson, stagesJson ->
            TurnPostCommitState(
                plan = Json.decodeFromString<TurnPostCommitPlan>(planJson),
                completedStages = Json.decodeFromString<Set<TurnPostCommitStage>>(stagesJson),
            )
        }.executeAsOneOrNull()
    }

    override suspend fun persistRelationshipEvaluation(
        turnId: String,
        evaluation: RelationshipEvaluation,
    ): RelationshipEvaluation = withContext(ioDispatcher) {
        var chosen = evaluation
        database.transaction {
            val current = queries.selectTurnPostCommit(turnId) { planJson, stagesJson ->
                TurnPostCommitState(
                    plan = Json.decodeFromString<TurnPostCommitPlan>(planJson),
                    completedStages = Json.decodeFromString<Set<TurnPostCommitStage>>(stagesJson),
                )
            }.executeAsOneOrNull() ?: error("No post-commit plan exists for turn '$turnId'")
            current.plan.relationshipEvaluation?.let { persisted ->
                chosen = persisted
                return@transaction
            }
            require(TurnPostCommitStage.RELATIONSHIP in current.plan.requiredStages) {
                "Turn '$turnId' does not require relationship evaluation"
            }
            queries.updateTurnPostCommitPlan(
                planJson = Json.encodeToString(current.plan.copy(relationshipEvaluation = evaluation)),
                turnId = turnId,
            )
        }
        chosen
    }

    override suspend fun markPostCommitStageCompleted(turnId: String, stage: TurnPostCommitStage) {
        withContext(ioDispatcher) {
            database.transaction {
                val current = queries.selectTurnPostCommit(turnId) { planJson, stagesJson ->
                    TurnPostCommitState(
                        plan = Json.decodeFromString<TurnPostCommitPlan>(planJson),
                        completedStages = Json.decodeFromString<Set<TurnPostCommitStage>>(stagesJson),
                    )
                }.executeAsOneOrNull() ?: error("No post-commit plan exists for turn '$turnId'")
                queries.updateTurnPostCommitStages(
                    completedStagesJson = Json.encodeToString(current.completedStages + stage),
                    turnId = turnId,
                )
            }
        }
    }

    override suspend fun promptHistory(
        sessionId: String,
        requiredTailTurns: Int,
        tokenBudget: Int,
    ): PromptHistorySnapshot = withContext(ioDispatcher) {
        val persistedState = queries.selectPromptHistoryState(
            sessionId = sessionId,
            mapper = ::mapPromptHistoryState,
        ).executeAsOneOrNull()
        val cacheEpoch = persistedState?.cacheEpoch ?: 0L
        val persistedChunks = persistedState?.let { state ->
            queries.selectPromptHistoryChunks(
                sessionId = sessionId,
                cacheEpoch = state.cacheEpoch,
                mapper = ::mapPromptHistoryChunk,
            ).executeAsList()
        }.orEmpty()
        val turns = queries.selectRecentForSession(
            sessionId = sessionId,
            limit = Long.MAX_VALUE,
            mapper = ::mapTurn,
        ).executeAsList().asReversed()
        val turnIds = turns.mapTo(mutableSetOf(), ConversationTurn::turnId)
        val stableChunks = persistedChunks.filter { chunk ->
            chunk.turnIds.all(turnIds::contains)
        }
        val snapshot = promptHistoryAssembler.assemble(
            sessionId = sessionId,
            turns = turns,
            requiredTailTurns = requiredTailTurns,
            tokenBudget = tokenBudget,
            existingStableChunks = stableChunks,
            cacheEpoch = cacheEpoch,
            storedSerializerVersion = persistedState?.serializerVersion
                ?: promptHistoryAssembler.serializer.serializerVersion,
        )

        database.transaction {
            queries.insertPromptHistoryState(
                session_id = sessionId,
                cache_epoch = snapshot.cacheEpoch,
                serializer_version = promptHistoryAssembler.serializer.serializerVersion.toLong(),
                updated_at_ms = clock.nowMs(),
            )
            snapshot.stableChunks.forEach { chunk ->
                queries.insertPromptHistoryChunk(
                    chunk_id = chunkId(chunk),
                    session_id = chunk.sessionId,
                    cache_epoch = chunk.cacheEpoch,
                    first_turn_id = chunk.firstTurnId,
                    last_turn_id = chunk.lastTurnId,
                    turn_ids_json = json.encodeToString(chunk.turnIds),
                    serialized_text = chunk.serializedText,
                    token_count = chunk.tokenCount.toLong(),
                    fingerprint = chunk.fingerprint,
                    serializer_version = chunk.serializerVersion.toLong(),
                )
            }
        }
        snapshot
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
        if (driver is JdbcSqliteDriver) driver.closeCurrentThreadConnection()
        driver.close()
        (ioDispatcher as? ExecutorCoroutineDispatcher)?.close()
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
        ioDispatcher: CoroutineDispatcher = newSqliteDispatcher("openeden-transcript-sqlite"),
        promptHistoryAssembler: PromptHistoryAssembler = PromptHistoryAssembler(),
        clock: RuntimeClock = SystemRuntimeClock,
    ): SqlDelightTranscriptStore = open(dbPath, Database.Schema, ioDispatcher, promptHistoryAssembler, clock)

        internal suspend fun open(
            dbPath: Path,
        schema: SqlSchema<QueryResult.Value<Unit>>,
        ioDispatcher: CoroutineDispatcher = newSqliteDispatcher("openeden-transcript-sqlite"),
        promptHistoryAssembler: PromptHistoryAssembler = PromptHistoryAssembler(),
        clock: RuntimeClock = SystemRuntimeClock,
        ): SqlDelightTranscriptStore = withContext(ioDispatcher) {
            val resolvedPath = dbPath.resolveForInitialization()
            initializationMutex.withLock {
                FileChannel.open(
                    resolvedPath.resolveSibling("${resolvedPath.fileName}.init.lock"),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                ).use { channel ->
                    channel.lock().use {
                        val driver = JdbcSqliteDriver("jdbc:sqlite:$resolvedPath", Properties())
                        val database = Database(driver)
                        try {
                            database.transaction {
                                val currentVersion = driver.readSchemaVersion()
                                when {
                                    currentVersion == 0L -> schema.create(driver).value
                                    currentVersion < schema.version -> {
                                        schema.migrate(driver, currentVersion, schema.version).value
                                    }
                                }
                                if (currentVersion < schema.version) {
                                    driver.writeSchemaVersion(schema.version)
                                }
                            }
                            driver.closeCurrentThreadConnection()
                            database.transcriptQueries.insertIncarnationIfAbsent(
                                active_incarnation_id = UUID.randomUUID().toString(),
                                created_at_ms = clock.nowMs(),
                            )
                            driver.closeCurrentThreadConnection()
                            SqlDelightTranscriptStore(database, driver, ioDispatcher, promptHistoryAssembler, clock)
                        } catch (failure: Throwable) {
                            runCatching { driver.closeCurrentThreadConnection() }
                                .exceptionOrNull()
                                ?.let(failure::addSuppressed)
                            throw failure
                        }
                    }
                }
            }
        }

        private val initializationMutex = Mutex()

        private fun JdbcSqliteDriver.closeCurrentThreadConnection() {
            closeConnection(getConnection())
        }

        private fun SqlDriver.readSchemaVersion(): Long {
            val mapper = { cursor: SqlCursor ->
                QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null)
            }
            return executeQuery(null, "PRAGMA user_version", mapper, 0).value ?: 0L
        }

        private fun SqlDriver.writeSchemaVersion(version: Long) {
            execute(null, "PRAGMA user_version = $version", 0).value
        }

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

        private val json = Json

        private fun chunkId(chunk: PromptHistoryChunk): String =
            "${chunk.sessionId}|${chunk.cacheEpoch}|${chunk.firstTurnId}"

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

        private fun mapPromptHistoryState(
            sessionId: String,
            cacheEpoch: Long,
            serializerVersion: Long,
            updatedAtMs: Long,
        ) = PromptHistoryState(sessionId, cacheEpoch, serializerVersion.toInt(), updatedAtMs)

        private fun mapPromptHistoryChunk(
            chunkId: String,
            sessionId: String,
            cacheEpoch: Long,
            firstTurnId: String,
            lastTurnId: String,
            turnIdsJson: String,
            serializedText: String,
            tokenCount: Long,
            fingerprint: String,
            serializerVersion: Long,
        ) = PromptHistoryChunk(
            sessionId = sessionId,
            cacheEpoch = cacheEpoch,
            firstTurnId = firstTurnId,
            lastTurnId = lastTurnId,
            turnIds = json.decodeFromString(turnIdsJson),
            serializedText = serializedText,
            tokenCount = tokenCount.toInt(),
            fingerprint = fingerprint,
            serializerVersion = serializerVersion.toInt(),
        )

        private data class PromptHistoryState(
            val sessionId: String,
            val cacheEpoch: Long,
            val serializerVersion: Int,
            val updatedAtMs: Long,
        )
    }
}
