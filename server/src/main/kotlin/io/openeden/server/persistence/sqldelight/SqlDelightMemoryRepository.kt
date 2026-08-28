package io.openeden.server.persistence.sqldelight

import io.openeden.server.db.Database
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.openeden.bio.BioVector
import io.openeden.bio.VectorDelta
import io.openeden.bio.VectorMapping
import io.openeden.identity.CanonicalSubjectResolver
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
import io.openeden.memory.MemoryVisibility
import io.openeden.memory.RetrievalMode
import io.openeden.memory.RetrievalRequest
import io.openeden.memory.RetrievalResult
import io.openeden.memory.RebuildableInMemoryVectorIndex
import io.openeden.memory.VectorIndex
import io.openeden.memory.VectorSearchHit
import io.openeden.memory.VectorSearchRequest
import io.openeden.memory.isVisibleTo
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
    private val canonicalSubjectResolver: CanonicalSubjectResolver = CanonicalSubjectResolver(),
) : MemoryStore, DiaryRawMemorySource {
    private val queries get() = database.memoryQueries
    private val localFallbackIndex = fallbackIndex ?: RebuildableInMemoryVectorIndex(inferenceExecutor)
    private val retrievalIndex = index ?: localFallbackIndex
    private var loadedIncarnationId: String? = null
    private val loadMutex = Mutex()

    suspend fun write(entry: MemoryEntry, modelId: String): Set<String> {
        return withContext(ioDispatcher) {
            require(modelId.isNotBlank()) { "modelId must not be blank" }
            val normalizedEntry = normalize(entry)
            val persistedLineage = PersistedMemoryLineage.encode(normalizedEntry.metadata.lineage, json)
            database.transaction {
                writeEntry(normalizedEntry, persistedLineage)
                queries.upsertEmbedding(normalizedEntry.id, modelId, json.encodeToString(normalizedEntry.semanticEmbedding), json.encodeToString(normalizedEntry.emotionalEmbedding), "READY")
                val nowMs = normalizedEntry.createdAtMs.takeIf { it > 0L } ?: createdAtMsFromId(normalizedEntry.id)
                queries.upsertVectorSync(normalizedEntry.id, modelId, "PENDING", 0, nowMs, null, nowMs)
                transactionFailureHook?.invoke()
            }
            if (modelId == activeModelId) {
                try { localFallbackIndex.insert(normalizedEntry) } catch (_: Throwable) { localFallbackIndex.markDirty() }
            } else {
                try { localFallbackIndex.remove(normalizedEntry.id) } catch (_: Throwable) { localFallbackIndex.markDirty() }
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
            loadMutex.withLock { loadedIncarnationId = null }
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
            DiaryRawMemoryCursor(it.id, it.createdAtMs, it.metadata)
        }

    override suspend fun firstRawMemoryAfter(sessionId: String, coveredRawMemoryId: String?): DiaryRawMemoryCursor? =
        rawMemoryRange(sessionId, coveredRawMemoryId, null, 1).firstOrNull()?.let {
            DiaryRawMemoryCursor(it.id, it.createdAtMs, it.metadata)
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

    override suspend fun retrieve(request: RetrievalRequest): RetrievalResult {
        val normalizedIncarnationId = request.incarnationId.trim()
        if (normalizedIncarnationId.isBlank()) {
            return RetrievalResult(
                mode = request.mode,
                injectionLabel = io.openeden.memory.RetrievalModeSelector.injectionLabel(request.mode),
                memories = emptyList(),
                diagnostics = io.openeden.memory.RetrievalDiagnostics(underfilled = true),
            )
        }
        val normalizedRequest = request.copy(incarnationId = normalizedIncarnationId)
        ensureIndexed(normalizedIncarnationId)
        return inferenceExecutor.run {
        var overfetchLimit = retrievalCandidateLimit()
            val positiveSkew = normalizedRequest.currentVector.copy(
                p = (normalizedRequest.currentVector.p + 0.3f).coerceAtMost(1.0f),
                v = (normalizedRequest.currentVector.v + 0.2f).coerceAtMost(1.0f),
            )
        val contrastTarget = VectorMapping.centerSymmetricTarget(normalizedRequest.currentVector, normalizedRequest.origin)
        val searchTargets = when (normalizedRequest.mode) {
            RetrievalMode.CONGRUENT -> listOf(normalizedRequest.currentVector)
            RetrievalMode.MIXED -> listOf(normalizedRequest.currentVector, positiveSkew)
            RetrievalMode.CONTRAST -> listOf(contrastTarget)
        }
        val semanticEmbedding = embeddingModel.embed(normalizedRequest.userInput)
        while (true) {
        val remotePools = buildList {
            for (emotionalTarget in searchTargets) {
                val targetEmbedding = embeddingModel.embed(emotionalTarget)
                val hits = retrievalIndex.search(
                    VectorSearchRequest(
                        sessionId = normalizedRequest.sessionId,
                        incarnationId = normalizedRequest.incarnationId,
                        canonicalSubjectId = normalizedRequest.canonicalSubjectId,
                        operatorAuthorized = normalizedRequest.operatorAuthorized,
                        semanticEmbedding = semanticEmbedding,
                        emotionalEmbedding = targetEmbedding,
                        limit = overfetchLimit,
                    ),
                // Qdrant unions two independent searches, each with overfetchLimit results.
                // Preserve that per-source capacity before hydrating; truncating at 3*K loses
                // emotional-only candidates that occur after the semantic side.
                ).take(remoteUnionLimit(overfetchLimit))
                val hydrated = hydrateRemoteCandidates(normalizedRequest, hits)
                val rangeExcludedIds = hits.asSequence()
                    .map { it.memoryId }
                    .filter { memoryId -> hydrated[memoryId]?.hasPersistedRangeOverlap(normalizedRequest) == true }
                    .toSet()
                add(
                    TargetMemoryPool(
                        targetEmbedding = targetEmbedding,
                        entries = hits.asSequence()
                            .mapNotNull { hit -> hydrated[hit.memoryId]?.entry }
                            .filterNot { it.id in rangeExcludedIds }
                            .distinctBy { it.id }
                            .toList(),
                        persistedRangeExcludedIds = rangeExcludedIds,
                        sourceExhausted = hits.size < overfetchLimit,
                    ),
                )
            }
        }
        val persistedRangeExcludedIds = remotePools
            .flatMap { it.persistedRangeExcludedIds }
            .toSet()
        val candidates = remotePools.flatMap { it.entries }.distinctBy { it.id }
        val targetPools = remotePools.map { it.targetEmbedding to it.entries }
        val palace = InMemoryMemoryPalace(
            inferenceExecutor = inferenceExecutor,
            maxResults = DEFAULT_MAX_RESULTS,
            embeddingModel = embeddingModel,
            index = TargetPoolVectorIndex(targetPools),
            preExcludedTurnLineageIds = persistedRangeExcludedIds,
        )
        candidates.forEach { palace.write(it) }
        val result = palace.retrieve(normalizedRequest)
        if (!result.underfilled || remotePools.all { it.sourceExhausted }) return@run result
        val nextLimit = doubledCandidateLimit(overfetchLimit)
        if (nextLimit <= overfetchLimit) return@run result
        overfetchLimit = nextLimit
        }
        error("progressive repository retrieval loop terminated unexpectedly")
        }
    }

    private suspend fun hydrateRemoteCandidates(
        request: RetrievalRequest,
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
                .filter { it.modelId == activeModelId }
                .filter { it.entry.isVisibleTo(request) }
                .associateBy { it.entry.id }
        }
    }

    suspend fun close() = withContext(ioDispatcher) {
        if (driver is JdbcSqliteDriver) driver.closeCurrentThreadConnection()
        driver.close()
        (ioDispatcher as? ExecutorCoroutineDispatcher)?.close()
    }

    private suspend fun ensureIndexed(incarnationId: String) {
        loadMutex.withLock {
            if (loadedIncarnationId == incarnationId) return
            val entries = withContext(ioDispatcher) {
                queries.selectByIncarnation(incarnationId, ::mapRow).executeAsList()
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
            if (indexed) loadedIncarnationId = incarnationId
        }
    }

    private fun retrievalCandidateLimit(): Int = when {
        candidateLimit <= 0 -> 0
        else -> maxOf(candidateLimit, DEFAULT_MAX_RESULTS * 3)
    }

    private fun remoteUnionLimit(perSourceLimit: Int): Int = when {
        perSourceLimit <= 0 -> 0
        perSourceLimit > Int.MAX_VALUE / 2 -> Int.MAX_VALUE
        else -> perSourceLimit * 2
    }

    private fun doubledCandidateLimit(limit: Int): Int =
        if (limit > Int.MAX_VALUE / 2) Int.MAX_VALUE else limit * 2

    private data class TargetMemoryPool(
        val targetEmbedding: List<Float>,
        val entries: List<MemoryEntry>,
        val persistedRangeExcludedIds: Set<String>,
        val sourceExhausted: Boolean,
    )

    private class TargetPoolVectorIndex(
        targetPools: List<Pair<List<Float>, List<MemoryEntry>>>,
    ) : VectorIndex {
        private val pools = targetPools.toMap()

        override suspend fun insert(entry: MemoryEntry) = Unit

        override suspend fun remove(memoryId: String) = Unit

        override suspend fun rebuild(entries: Iterable<MemoryEntry>, batchSize: Int) = Unit

        override suspend fun search(request: VectorSearchRequest): List<VectorSearchHit> =
            pools[request.emotionalEmbedding].orEmpty()
                // The pool is already bounded to both remote source limits. Applying the
                // single-source request limit here would discard emotional-only union hits.
                .map { entry ->
                    VectorSearchHit(
                        memoryId = entry.id,
                        entry = entry,
                        semanticSimilarity = 0.0f,
                        emotionalSimilarity = 0.0f,
                    )
                }

        override suspend fun markDirty() = Unit
    }

    private fun writeEntry(entry: MemoryEntry, persistedLineage: PersistedMemoryLineage.EncodedLineage) {
        val snapshot = entry.metadata.snapshot8D
        val delta = entry.metadata.deltaVec
        val origin = entry.metadata.snapshotOrigin
        queries.insertEntry(
            id = entry.id,
            session_id = entry.sessionId,
            user_id = entry.metadata.userId,
            platform = entry.metadata.platform,
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
            incarnation_id = entry.metadata.incarnationId,
            source_session_id = entry.metadata.sourceSessionId,
            canonical_subject_id = entry.metadata.canonicalSubjectId,
            visibility_kind = entry.metadata.visibility.persistenceKind(),
            visibility_subject_id = entry.metadata.visibility.persistenceSubjectId(),
            visibility_session_id = entry.metadata.visibility.persistenceSessionId(),
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
        incarnationId: String, sourceSessionId: String, canonicalSubjectId: String,
        visibilityKind: String, visibilitySubjectId: String?, visibilitySessionId: String?,
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
                incarnationId = incarnationId,
                sourceSessionId = sourceSessionId,
                canonicalSubjectId = canonicalSubjectId,
                visibility = memoryVisibilityFromPersistence(
                    kind = visibilityKind,
                    subjectId = visibilitySubjectId,
                    sessionId = visibilitySessionId,
                ),
                platform = platform,
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

    private suspend fun normalize(entry: MemoryEntry): MemoryEntry {
        val platform = entry.sessionId.substringBefore(':', entry.sessionId)
        val subjectId = entry.metadata.canonicalSubjectId.ifBlank {
            canonicalSubjectResolver.resolve(platform, entry.metadata.userId).value
        }
        val sourceSessionId = entry.metadata.sourceSessionId.ifBlank { entry.sessionId }
        val visibility = entry.metadata.visibility.normalize(sourceSessionId, subjectId)
        return entry.copy(
            metadata = entry.metadata.copy(
                incarnationId = entry.metadata.incarnationId.ifBlank { activeIncarnationId() },
                sourceSessionId = sourceSessionId,
                canonicalSubjectId = subjectId,
                visibility = visibility,
                platform = entry.metadata.platform.ifBlank { entry.sessionId.substringBefore(':', entry.sessionId) },
            ),
        )
    }

    private suspend fun activeIncarnationId(): String = withContext(ioDispatcher) {
        database.transcriptQueries
            .selectActiveIncarnation { incarnationId, _ -> incarnationId }
            .executeAsOneOrNull()
            ?: LEGACY_INCARNATION_ID
    }

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
        private const val LEGACY_INCARNATION_ID = "legacy-incarnation"

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
            canonicalSubjectResolver: CanonicalSubjectResolver = CanonicalSubjectResolver(),
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
                canonicalSubjectResolver = canonicalSubjectResolver,
            )
        }

        private fun JdbcSqliteDriver.closeCurrentThreadConnection() {
            closeConnection(getConnection())
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
