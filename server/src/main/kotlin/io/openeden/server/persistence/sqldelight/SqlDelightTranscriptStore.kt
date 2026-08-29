package io.openeden.server.persistence.sqldelight

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.openeden.hash.Sha256
import io.openeden.server.db.Database
import io.openeden.transcript.ActiveIncarnation
import io.openeden.transcript.ConversationHistoryPage
import io.openeden.transcript.ConversationTurn
import io.openeden.transcript.HistoryCursor
import io.openeden.transcript.InvalidHistoryCursorException
import io.openeden.transcript.PromptHistoryAssembler
import io.openeden.transcript.PromptHistoryChunk
import io.openeden.transcript.PromptHistoryCompactor
import io.openeden.transcript.PromptHistorySerializer
import io.openeden.transcript.PromptHistorySnapshot
import io.openeden.transcript.PromptHistorySummary
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
import kotlinx.coroutines.CancellationException
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
    private val promptHistoryCompactionMutex = Mutex()

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
            val incarnationId = queries.selectActiveIncarnation { id, _ -> id }.executeAsOne()

            queries.selectRecentForSession(
                incarnationId = incarnationId,
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
        val incarnationId = queries.selectActiveIncarnation { id, _ -> id }.executeAsOne()
        val persistedState = queries.selectPromptHistoryState(
            incarnationId = incarnationId,
            sessionId = sessionId,
            mapper = ::mapPromptHistoryState,
        ).executeAsOneOrNull()
        val cacheEpoch = persistedState?.cacheEpoch ?: 0L
        val persistedChunks = persistedState?.let { state ->
            queries.selectPromptHistoryChunks(
                incarnationId = incarnationId,
                sessionId = sessionId,
                cacheEpoch = state.cacheEpoch,
                mapper = ::mapPromptHistoryChunk,
            ).executeAsList()
        }.orEmpty()
        val turns = queries.selectRecentForSession(
            incarnationId = incarnationId,
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
            existingSummary = persistedState?.summary,
            cacheEpoch = cacheEpoch,
            storedSerializerVersion = persistedState?.serializerVersion
                ?: promptHistoryAssembler.serializer.serializerVersion,
        )

        database.transaction {
            queries.insertPromptHistoryState(
                incarnation_id = incarnationId,
                session_id = sessionId,
                cache_epoch = snapshot.cacheEpoch,
                serializer_version = promptHistoryAssembler.serializer.serializerVersion.toLong(),
                updated_at_ms = clock.nowMs(),
            )
            queries.updatePromptHistoryStateMetadata(
                incarnationId = incarnationId,
                cacheEpoch = snapshot.cacheEpoch,
                serializerVersion = promptHistoryAssembler.serializer.serializerVersion.toLong(),
                updatedAtMs = clock.nowMs(),
                sessionId = sessionId,
                expectedCacheEpoch = cacheEpoch,
            )
            snapshot.stableChunks.forEach { chunk ->
                queries.insertPromptHistoryChunk(
                    incarnation_id = incarnationId,
                    chunk_id = chunkId(chunk),
                    session_id = chunk.sessionId,
                    cache_epoch = chunk.cacheEpoch,
                    first_turn_id = chunk.firstTurnId,
                    last_turn_id = chunk.lastTurnId,
                    turn_ids_json = json.encodeToString(chunk.turnIds),
                    items_json = json.encodeToString(chunk.items),
                    serialized_text = null,
                    token_count = chunk.tokenCount.toLong(),
                    fingerprint = chunk.fingerprint,
                    serializer_version = chunk.serializerVersion.toLong(),
                )
            }
        }
        snapshot
    }

    override suspend fun compactPromptHistory(
        sessionId: String,
        requestId: String,
        requiredTailTurns: Int,
        tokenBudget: Int,
        compactor: PromptHistoryCompactor,
    ): PromptHistorySnapshot = promptHistoryCompactionMutex.withLock {
        require(requestId.isNotBlank()) { "requestId must not be blank" }
        completedCompaction(requestId, sessionId)?.let { return@withLock it }

        val source = promptHistory(sessionId, requiredTailTurns, tokenBudget)
        completedCompaction(requestId, sessionId)?.let { return@withLock it }
        val proposed = try {
            compactor.compact(requestId, source)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            source
        }.takeIf { candidate -> candidate.isValidSuccessorOf(source) } ?: source

        try {
            persistCompaction(requestId, sessionId, source, proposed)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            source
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
        if (driver is JdbcSqliteDriver) driver.closeCurrentThreadConnection()
        driver.close()
        (ioDispatcher as? ExecutorCoroutineDispatcher)?.close()
    }

    private fun ConversationTurn.toCursor(incarnationId: String) = HistoryCursor(
        incarnationId = incarnationId,
        completedAtMs = completedAtMs,
        turnId = turnId,
    )

    private suspend fun completedCompaction(
        requestId: String,
        sessionId: String,
    ): PromptHistorySnapshot? = withContext(ioDispatcher) {
        val incarnationId = queries.selectActiveIncarnation { id, _ -> id }.executeAsOne()
        queries.selectPromptHistoryCompaction(incarnationId, requestId, ::mapPromptHistoryCompaction)
            .executeAsOneOrNull()
            ?.completedSnapshot(sessionId)
    }

    private suspend fun persistCompaction(
        requestId: String,
        sessionId: String,
        source: PromptHistorySnapshot,
        proposed: PromptHistorySnapshot,
    ): PromptHistorySnapshot = withContext(ioDispatcher) {
        val incarnationId = queries.selectActiveIncarnation { id, _ -> id }.executeAsOne()
        val sourceJson = json.encodeToString(source)
        val sourceFingerprint = Sha256.hex(sourceJson.encodeToByteArray())
        var persisted = source
        database.transaction {
            val existing = queries.selectPromptHistoryCompaction(incarnationId, requestId, ::mapPromptHistoryCompaction)
                .executeAsOneOrNull()
            if (existing != null) {
                existing.completedSnapshot(sessionId)?.let { completed ->
                    persisted = completed
                    return@transaction
                }
                require(existing.sessionId == sessionId && existing.sourceFingerprint == sourceFingerprint) {
                    "requestId was already used for another prompt history compaction"
                }
            } else {
                queries.insertPromptHistoryCompactionIfAbsent(
                    incarnation_id = incarnationId,
                    request_id = requestId,
                    session_id = sessionId,
                    source_fingerprint = sourceFingerprint,
                    created_at_ms = clock.nowMs(),
                )
            }

            persisted = proposed
            if (proposed != source) {
                val summary = checkNotNull(proposed.summary)
                queries.activatePromptHistorySummary(
                    incarnationId = incarnationId,
                    newCacheEpoch = proposed.cacheEpoch,
                    summaryText = summary.text,
                    summarySourceTurnIdsJson = json.encodeToString(summary.sourceTurnIds),
                    summaryFingerprint = summary.fingerprint,
                    summarySerializerVersion = summary.serializerVersion.toLong(),
                    updatedAtMs = clock.nowMs(),
                    sessionId = sessionId,
                    expectedCacheEpoch = source.cacheEpoch,
                )
                if (queries.selectChanges().executeAsOne() != 1L) persisted = source
            }

            val resultJson = json.encodeToString(persisted)
            queries.completePromptHistoryCompaction(
                incarnationId = incarnationId,
                resultFingerprint = Sha256.hex(resultJson.encodeToByteArray()),
                resultSnapshotJson = resultJson,
                resultCacheEpoch = persisted.cacheEpoch,
                completedAtMs = clock.nowMs(),
                requestId = requestId,
                sessionId = sessionId,
                sourceFingerprint = sourceFingerprint,
            )
            check(queries.selectChanges().executeAsOne() == 1L) {
                "Prompt history compaction request was not completed"
            }
        }
        persisted
    }

    private fun PromptHistorySnapshot.isValidSuccessorOf(source: PromptHistorySnapshot): Boolean =
        this == source ||
            source.cacheEpoch < Long.MAX_VALUE &&
            cacheEpoch == source.cacheEpoch + 1L &&
            stableChunks.isEmpty() &&
            summary != null &&
            mutableTail == source.mutableTail &&
            sourceTurnIds == source.sourceTurnIds

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
            summaryText: String?,
            summarySourceTurnIdsJson: String?,
            summaryFingerprint: String?,
            summarySerializerVersion: Long?,
        ): PromptHistoryState {
            val summaryValues = listOf(
                summaryText,
                summarySourceTurnIdsJson,
                summaryFingerprint,
                summarySerializerVersion,
            )
            require(summaryValues.all { it == null } || summaryValues.none { it == null }) {
                "Prompt history summary columns must be either all null or all present"
            }
            val summary = summaryText?.let { text ->
                PromptHistorySummary(
                    text = text,
                    sourceTurnIds = json.decodeFromString(checkNotNull(summarySourceTurnIdsJson)),
                    fingerprint = checkNotNull(summaryFingerprint),
                    serializerVersion = checkNotNull(summarySerializerVersion).toInt(),
                )
            }
            return PromptHistoryState(sessionId, cacheEpoch, serializerVersion.toInt(), updatedAtMs, summary)
        }

        private fun mapPromptHistoryCompaction(
            requestId: String,
            sessionId: String,
            sourceFingerprint: String,
            status: String,
            resultFingerprint: String?,
            resultSnapshotJson: String?,
            resultCacheEpoch: Long?,
            createdAtMs: Long,
            completedAtMs: Long?,
        ) = PromptHistoryCompactionRow(
            requestId = requestId,
            sessionId = sessionId,
            sourceFingerprint = sourceFingerprint,
            status = status,
            resultFingerprint = resultFingerprint,
            resultSnapshotJson = resultSnapshotJson,
            resultCacheEpoch = resultCacheEpoch,
            createdAtMs = createdAtMs,
            completedAtMs = completedAtMs,
        )

        private fun mapPromptHistoryChunk(
            chunkId: String,
            sessionId: String,
            cacheEpoch: Long,
            firstTurnId: String,
            lastTurnId: String,
            turnIdsJson: String,
            itemsJson: String?,
            serializedText: String?,
            tokenCount: Long,
            fingerprint: String,
            serializerVersion: Long,
        ): PromptHistoryChunk {
            val version = serializerVersion.toInt()
            val serializer = PromptHistorySerializer(serializerVersion = version)
            val items = when {
                !itemsJson.isNullOrBlank() -> json.decodeFromString(itemsJson)
                serializedText != null -> serializer.deserializeLegacy(serializedText)
                else -> error("Prompt history chunk '$chunkId' has no recoverable items")
            }
            val chunk = PromptHistoryChunk(
                sessionId = sessionId,
                cacheEpoch = cacheEpoch,
                items = items,
                tokenCount = tokenCount.toInt(),
                serializerVersion = version,
            )
            require(chunk.firstTurnId == firstTurnId && chunk.lastTurnId == lastTurnId) {
                "Prompt history chunk '$chunkId' boundaries do not match its items"
            }
            require(chunk.turnIds == json.decodeFromString<List<String>>(turnIdsJson)) {
                "Prompt history chunk '$chunkId' lineage does not match its items"
            }
            if (itemsJson != null) {
                require(chunk.fingerprint == fingerprint) {
                    "Prompt history chunk '$chunkId' fingerprint does not match its items"
                }
            }
            return chunk
        }

        private data class PromptHistoryState(
            val sessionId: String,
            val cacheEpoch: Long,
            val serializerVersion: Int,
            val updatedAtMs: Long,
            val summary: PromptHistorySummary?,
        )

        private data class PromptHistoryCompactionRow(
            val requestId: String,
            val sessionId: String,
            val sourceFingerprint: String,
            val status: String,
            val resultFingerprint: String?,
            val resultSnapshotJson: String?,
            val resultCacheEpoch: Long?,
            val createdAtMs: Long,
            val completedAtMs: Long?,
        ) {
            fun completedSnapshot(expectedSessionId: String): PromptHistorySnapshot? {
                require(sessionId == expectedSessionId) {
                    "requestId was already used for another prompt history session"
                }
                if (status != "COMPLETED") return null
                val snapshotJson = checkNotNull(resultSnapshotJson)
                require(Sha256.hex(snapshotJson.encodeToByteArray()) == resultFingerprint) {
                    "Persisted prompt history compaction fingerprint does not match its result"
                }
                val snapshot = json.decodeFromString<PromptHistorySnapshot>(snapshotJson)
                require(snapshot.cacheEpoch == resultCacheEpoch) {
                    "Persisted prompt history compaction epoch does not match its result"
                }
                return snapshot
            }
        }
    }
}
