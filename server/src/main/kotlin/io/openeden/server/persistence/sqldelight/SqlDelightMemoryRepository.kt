package io.openeden.server.persistence.sqldelight

import io.openeden.server.db.Database
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.openeden.bio.BioVector
import io.openeden.bio.VectorDelta
import io.openeden.memory.DeterministicMemoryEmbeddingModel
import io.openeden.memory.InMemoryMemoryPalace
import io.openeden.memory.MemoryEntry
import io.openeden.memory.MemoryEmbeddingModel
import io.openeden.memory.MemoryKind
import io.openeden.memory.MemoryMetadata
import io.openeden.memory.MemoryRetriever
import io.openeden.memory.MemorySnippet
import io.openeden.memory.MemoryRoom
import io.openeden.memory.MemoryStore
import io.openeden.memory.RetrievalRequest
import io.openeden.memory.RetrievalResult
import io.openeden.memory.RebuildableInMemoryVectorIndex
import io.openeden.memory.VectorIndex
import io.openeden.memory.VectorSearchHit
import io.openeden.memory.VectorSearchRequest
import io.openeden.runtime.inference.DirectInferenceExecutor
import io.openeden.runtime.inference.InferenceExecutor
import io.openeden.runtime.diary.DiaryRawMemoryCursor
import io.openeden.runtime.diary.DiaryRawMemorySource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

data class StoredMemory(
    val entry: MemoryEntry,
    val modelId: String,
    internal val persistedSourceTurnIdsJson: String = "[]",
    internal val persistedSourceMemoryIdsJson: String = "[]",
)

class SqlDelightMemoryRepository(
    private val database: Database,
    private val driver: SqlDriver,
    private val embeddingModel: MemoryEmbeddingModel = DeterministicMemoryEmbeddingModel,
    private val json: Json = Json,
    private val activeModelId: String = "local-v1",
    private val projectionWake: () -> Unit = {},
    private val transactionFailureHook: (() -> Unit)? = null,
    private val index: VectorIndex? = null,
    private val candidateLimit: Int = 128,
    private val fallbackIndex: RebuildableInMemoryVectorIndex? = null,
    private val ioDispatcher: CoroutineDispatcher = newSqliteDispatcher("openeden-memory-sqlite"),
    private val inferenceExecutor: InferenceExecutor = DirectInferenceExecutor,
) : MemoryStore, DiaryRawMemorySource {
    private val queries get() = database.memoryQueries
    private val localFallbackIndex = fallbackIndex ?: RebuildableInMemoryVectorIndex(inferenceExecutor)
    private val retrievalIndex = index ?: localFallbackIndex
    private val loadedSessions = mutableSetOf<String>()
    private val loadMutex = Mutex()

    suspend fun write(entry: MemoryEntry, modelId: String): Set<String> {
        return withContext(ioDispatcher) {
            require(modelId.isNotBlank()) { "modelId must not be blank" }
            val persistedLineage = PersistedMemoryLineage.encode(entry.metadata.lineage, json)
            database.transaction {
                writeEntry(entry, persistedLineage)
                queries.upsertEmbedding(entry.id, modelId, json.encodeToString(entry.semanticEmbedding), json.encodeToString(entry.emotionalEmbedding), "READY")
                val nowMs = entry.createdAtMs.takeIf { it > 0L } ?: createdAtMsFromId(entry.id)
                queries.upsertVectorSync(entry.id, modelId, "PENDING", 0, nowMs, null, nowMs)
                transactionFailureHook?.invoke()
            }
            if (modelId == activeModelId) {
                try { localFallbackIndex.insert(entry) } catch (_: Throwable) { localFallbackIndex.markDirty() }
            } else {
                try { localFallbackIndex.remove(entry.id) } catch (_: Throwable) { localFallbackIndex.markDirty() }
            }
            try {
                projectionWake()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Wake delivery is best effort after the durable write and fallback update.
            }
            setOf(io.openeden.trace.TraceTag.MemoryWritten)
        }
    }

    override suspend fun write(entry: MemoryEntry): Set<String> = write(entry, activeModelId)

    suspend fun readById(id: String): StoredMemory? = withContext(ioDispatcher) {
        queries.selectById(id, ::mapRow).executeAsOneOrNull()
    }

    /** Rebuilds local embeddings whose model differs from [activeModelId], in bounded batches. */
    suspend fun refreshOutdatedEmbeddings(
        inferenceExecutor: InferenceExecutor,
        batchSize: Int = 128,
    ): Int = withContext(ioDispatcher) {
        require(batchSize > 0) { "batchSize must be positive" }
        var refreshedCount = 0
        while (true) {
            val candidates = queries.selectVectorSyncForModelRefresh(
                activeModelId,
                batchSize.toLong(),
                ::mapRefreshCandidate,
            ).executeAsList()
            if (candidates.isEmpty()) break

            val ids = candidates.map { it.memoryId }
            val entries = queries.selectByIds(ids, ::mapRow).executeAsList()
            val refreshed = inferenceExecutor.run {
                entries.map { stored ->
                    RefreshedEmbedding(
                        memoryId = stored.entry.id,
                        semantic = embeddingModel.embed(stored.entry.content),
                        emotional = embeddingModel.embed(stored.entry.metadata.snapshot8D),
                    )
                }
            }
            val nowMs = System.currentTimeMillis()
            database.transaction {
                refreshed.forEach { embedding ->
                    queries.upsertEmbedding(
                        embedding.memoryId,
                        activeModelId,
                        json.encodeToString(embedding.semantic),
                        json.encodeToString(embedding.emotional),
                        "READY",
                    )
                    queries.upsertVectorSync(
                        embedding.memoryId,
                        activeModelId,
                        MemoryVectorProjectionStore.ProjectionStatus.PENDING.name,
                        0,
                        nowMs,
                        null,
                        nowMs,
                    )
                }
            }
            refreshedCount += refreshed.size
            loadMutex.withLock { loadedSessions.clear() }
            localFallbackIndex.markDirty()
            if (refreshed.size >= batchSize) yield()
        }
        refreshedCount
    }

    /** Ordered RAW-only cursor range, with an exclusive lower and inclusive upper bound. */
    suspend fun rawMemoryRange(
        sessionId: String,
        afterId: String?,
        throughId: String?,
        limit: Int,
    ): List<MemoryEntry> {
        if (limit <= 0) return emptyList()
        return withContext(ioDispatcher) {
            val afterMs = afterId?.let(::createdAtMsFromId)
            val throughMs = throughId?.let(::createdAtMsFromId)
            queries.selectRawMemoryRange(sessionId, afterId ?: "", afterMs ?: 0L, afterMs ?: 0L, afterId ?: "", throughId ?: "", throughMs ?: 0L, throughMs ?: 0L, throughId ?: "", limit.toLong(), ::mapRow)
                .executeAsList().map { it.entry }
        }
    }

    override suspend fun sessionsWithRawMemories(): Set<String> = withContext(ioDispatcher) {
        queries.selectRawSessions().executeAsList().toSet()
    }

    override suspend fun latestRawMemory(sessionId: String): DiaryRawMemoryCursor? =
        withContext(ioDispatcher) { queries.selectLatestRawMemory(sessionId, ::mapRow).executeAsOneOrNull()?.entry }?.let {
            DiaryRawMemoryCursor(it.id, it.createdAtMs)
        }

    override suspend fun firstRawMemoryAfter(sessionId: String, coveredRawMemoryId: String?): DiaryRawMemoryCursor? =
        rawMemoryRange(sessionId, coveredRawMemoryId, null, 1).firstOrNull()?.let {
            DiaryRawMemoryCursor(it.id, it.createdAtMs)
        }

    override suspend fun stableVectors(sessionId: String, limit: Int): List<BioVector> = withContext(ioDispatcher) {
        queries.selectStable(sessionId, limit.toLong(), ::mapVector).executeAsList()
    }

    override suspend fun recent(sessionId: String, limit: Int): List<MemorySnippet> = withContext(ioDispatcher) {
        queries.selectRecent(sessionId, limit.toLong(), ::mapRow)
            .executeAsList()
            .map { stored ->
                MemorySnippet(
                    id = stored.entry.id,
                    content = stored.entry.content,
                    metadata = stored.entry.metadata,
                    createdAtMs = stored.entry.createdAtMs,
                )
            }
            .asReversed()
    }

    override suspend fun retrieve(request: RetrievalRequest): RetrievalResult = inferenceExecutor.run {
        ensureIndexed(request.sessionId)
        val overfetchLimit = retrievalCandidateLimit()
        val hits = retrievalIndex.search(
            VectorSearchRequest(
                sessionId = request.sessionId,
                semanticEmbedding = embeddingModel.embed(request.userInput),
                emotionalEmbedding = embeddingModel.embed(request.currentVector),
                limit = overfetchLimit,
            ),
        ).take(overfetchLimit)
        val hydrated = hydrateRemoteCandidates(request.sessionId, hits)
        var persistedRangeExcludedCount = 0
        val candidates = hits.mapNotNull { hit ->
            val stored = hydrated[hit.memoryId] ?: return@mapNotNull null
            if (stored.hasPersistedRangeOverlap(request)) {
                persistedRangeExcludedCount += 1
                null
            } else {
                stored.entry
            }
        }
        val palace = InMemoryMemoryPalace(
            inferenceExecutor = inferenceExecutor,
            maxResults = DEFAULT_MAX_RESULTS,
            embeddingModel = embeddingModel,
        )
        candidates.forEach { palace.write(it) }
        palace.retrieve(request).let { result ->
            if (persistedRangeExcludedCount == 0) result else result.copy(
                lineageExcludedCount = result.lineageExcludedCount + persistedRangeExcludedCount,
            )
        }
    }

    private suspend fun hydrateRemoteCandidates(
        sessionId: String,
        hits: List<VectorSearchHit>,
    ): Map<String, StoredMemory> {
        val ids = hits.asSequence()
            .map { it.memoryId }
            .distinct()
            .toList()
        if (ids.isEmpty()) return emptyMap()
        return withContext(ioDispatcher) {
            queries.selectByIds(ids, ::mapRow).executeAsList()
                .asSequence()
                .filter { it.entry.sessionId == sessionId && it.modelId == activeModelId }
                .associateBy { it.entry.id }
        }
    }

    suspend fun close() = withContext(ioDispatcher) {
        if (driver is JdbcSqliteDriver) driver.closeCurrentThreadConnection()
        driver.close()
        (ioDispatcher as? ExecutorCoroutineDispatcher)?.close()
    }

    private suspend fun ensureIndexed(sessionId: String) {
        loadMutex.withLock {
            if (sessionId in loadedSessions) return
            val entries = withContext(ioDispatcher) {
                queries.selectBySession(sessionId, ::mapRow).executeAsList()
                    .filter { it.modelId == activeModelId }
                    .map { it.entry }
            }
            var indexed = true
            try {
                localFallbackIndex.rebuild(entries)
            } catch (_: Throwable) {
                localFallbackIndex.markDirty()
                indexed = false
            }
            if (indexed) loadedSessions += sessionId
        }
    }

    private fun retrievalCandidateLimit(): Int = when {
        candidateLimit <= 0 -> 0
        else -> maxOf(candidateLimit, DEFAULT_MAX_RESULTS * 3)
    }

    private fun writeEntry(entry: MemoryEntry, persistedLineage: PersistedMemoryLineage.EncodedLineage) {
        val snapshot = entry.metadata.snapshot8D
        val delta = entry.metadata.deltaVec
        val origin = entry.metadata.snapshotOrigin
        queries.insertEntry(
            id = entry.id,
            session_id = entry.sessionId,
            user_id = entry.metadata.userId,
            platform = entry.sessionId.substringBefore(':', entry.sessionId),
            room = entry.room.name,
            kind = entry.kind.name,
            content = entry.content,
            tags_json = json.encodeToString(entry.tags.toList()),
            created_at_ms = entry.createdAtMs.takeIf { it > 0L } ?: createdAtMsFromId(entry.id),
            snapshot_l = snapshot.l.toDouble(), snapshot_p = snapshot.p.toDouble(),
            snapshot_e = snapshot.e.toDouble(), snapshot_s = snapshot.s.toDouble(),
            snapshot_tau = snapshot.tau.toDouble(), snapshot_v = snapshot.v.toDouble(),
            snapshot_m = snapshot.m.toDouble(), snapshot_f = snapshot.f.toDouble(),
            omega_state = entry.metadata.omegaState.toDouble(),
            delta_l = delta.l.toDouble(), delta_p = delta.p.toDouble(),
            delta_e = delta.e.toDouble(), delta_s = delta.s.toDouble(),
            delta_tau = delta.tau.toDouble(), delta_v = delta.v.toDouble(),
            delta_m = delta.m.toDouble(), delta_f = delta.f.toDouble(),
            origin_l = origin.l.toDouble(), origin_p = origin.p.toDouble(),
            origin_e = origin.e.toDouble(), origin_s = origin.s.toDouble(),
            origin_tau = origin.tau.toDouble(), origin_v = origin.v.toDouble(),
            origin_m = origin.m.toDouble(), origin_f = origin.f.toDouble(),
            source_turn_ids_json = persistedLineage.sourceTurnIdsJson,
            source_memory_ids_json = persistedLineage.sourceMemoryIdsJson,
            content_fingerprint = entry.metadata.contentFingerprint,
            lineage_version = persistedLineage.lineageVersion.toLong(),
        )
    }

    @Suppress("LongParameterList")
    private fun mapRow(
        id: String, sessionId: String, userId: String, platform: String, room: String, kind: String,
        content: String, tagsJson: String, createdAtMs: Long,
        snapshotL: Double, snapshotP: Double, snapshotE: Double, snapshotS: Double,
        snapshotTau: Double, snapshotV: Double, snapshotM: Double, snapshotF: Double,
        omegaState: Double, deltaL: Double, deltaP: Double, deltaE: Double, deltaS: Double,
        deltaTau: Double, deltaV: Double, deltaM: Double, deltaF: Double,
        originL: Double, originP: Double, originE: Double, originS: Double,
        originTau: Double, originV: Double, originM: Double, originF: Double,
        sourceTurnIdsJson: String, sourceMemoryIdsJson: String, contentFingerprint: String?, lineageVersion: Long,
        modelId: String?, semanticJson: String?, emotionalJson: String?, status: String?,
    ): StoredMemory {
        val snapshot = BioVector(snapshotL.toFloat(), snapshotP.toFloat(), snapshotE.toFloat(), snapshotS.toFloat(), snapshotTau.toFloat(), snapshotV.toFloat(), snapshotM.toFloat(), snapshotF.toFloat())
        val origin = BioVector(originL.toFloat(), originP.toFloat(), originE.toFloat(), originS.toFloat(), originTau.toFloat(), originV.toFloat(), originM.toFloat(), originF.toFloat())
        val entry = MemoryEntry(
            id = id, sessionId = sessionId, content = content,
            room = MemoryRoom.valueOf(room), kind = MemoryKind.valueOf(kind),
            tags = json.decodeFromString(tagsJson),
            semanticEmbedding = semanticJson?.let { json.decodeFromString(it) } ?: emptyList(),
            emotionalEmbedding = emotionalJson?.let { json.decodeFromString(it) } ?: emptyList(),
            metadata = MemoryMetadata(
                snapshot8D = snapshot, omegaState = omegaState.toFloat(),
                deltaVec = VectorDelta(deltaL.toFloat(), deltaP.toFloat(), deltaE.toFloat(), deltaS.toFloat(), deltaTau.toFloat(), deltaV.toFloat(), deltaM.toFloat(), deltaF.toFloat()),
                snapshotOrigin = origin, userId = userId,
                lineage = PersistedMemoryLineage.decode(
                    sourceTurnIdsJson = sourceTurnIdsJson,
                    sourceMemoryIdsJson = sourceMemoryIdsJson,
                    lineageVersion = lineageVersion,
                    json = json,
                ),
                contentFingerprint = contentFingerprint,
            ),
            createdAtMs = createdAtMs,
        )
        return StoredMemory(
            entry = entry,
            modelId = modelId ?: "missing",
            persistedSourceTurnIdsJson = sourceTurnIdsJson,
            persistedSourceMemoryIdsJson = sourceMemoryIdsJson,
        )
    }

    private fun StoredMemory.hasPersistedRangeOverlap(request: RetrievalRequest): Boolean {
        val exactTurnOverlap = entry.metadata.lineage.sourceTurnIds.any {
            it in request.exclusionContext.sourceTurnIds
        }
        return !exactTurnOverlap &&
            PersistedMemoryLineage.overlaps(
                persistedJson = persistedSourceTurnIdsJson,
                candidateIds = request.exclusionContext.sourceTurnIds,
                sourceTurns = true,
                json = json,
            )
    }

    private fun mapVector(
        l: Double, p: Double, e: Double, s: Double, tau: Double, v: Double, m: Double, f: Double,
    ): BioVector = BioVector(l.toFloat(), p.toFloat(), e.toFloat(), s.toFloat(), tau.toFloat(), v.toFloat(), m.toFloat(), f.toFloat())

    private fun mapRefreshCandidate(
        memoryId: String,
        modelId: String,
        status: String,
        attempts: Long,
        availableAtMs: Long,
        lastError: String?,
        updatedAtMs: Long,
    ) = RefreshCandidate(memoryId, modelId, status, attempts, availableAtMs, lastError, updatedAtMs)

    private data class RefreshCandidate(
        val memoryId: String,
        val modelId: String,
        val status: String,
        val attempts: Long,
        val availableAtMs: Long,
        val lastError: String?,
        val updatedAtMs: Long,
    )

    private data class RefreshedEmbedding(
        val memoryId: String,
        val semantic: List<Float>,
        val emotional: List<Float>,
    )

    companion object {
        private const val DEFAULT_MAX_RESULTS = 10

        fun open(
            dbPath: Path,
            embeddingModel: MemoryEmbeddingModel = DeterministicMemoryEmbeddingModel,
            activeModelId: String = "local-v1",
            projectionWake: () -> Unit = {},
            transactionFailureHook: (() -> Unit)? = null,
            index: VectorIndex? = null,
            candidateLimit: Int = 128,
            fallbackIndex: RebuildableInMemoryVectorIndex? = null,
            inferenceExecutor: InferenceExecutor = DirectInferenceExecutor,
        ): SqlDelightMemoryRepository {
            dbPath.parent?.let { Files.createDirectories(it) }
            val driver = JdbcSqliteDriver("jdbc:sqlite:${dbPath.toAbsolutePath()}", Properties(), Database.Schema)
            driver.closeCurrentThreadConnection()
            return SqlDelightMemoryRepository(
                database = Database(driver),
                driver = driver,
                embeddingModel = embeddingModel,
                activeModelId = activeModelId,
                projectionWake = projectionWake,
                transactionFailureHook = transactionFailureHook,
                index = index,
                candidateLimit = candidateLimit,
                fallbackIndex = fallbackIndex,
                inferenceExecutor = inferenceExecutor,
            )
        }

        private fun JdbcSqliteDriver.closeCurrentThreadConnection() {
            closeConnection(getConnection())
        }
    }
}
