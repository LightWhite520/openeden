package io.openeden.server.db

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
import io.openeden.memory.MemoryRoom
import io.openeden.memory.MemoryStore
import io.openeden.memory.RetrievalRequest
import io.openeden.memory.RetrievalResult
import io.openeden.memory.RebuildableInMemoryVectorIndex
import io.openeden.memory.VectorSearchRequest
import io.openeden.runtime.DirectInferenceExecutor
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

data class StoredMemory(
    val entry: MemoryEntry,
    val modelId: String,
)

class SqlDelightMemoryRepository(
    private val database: Database,
    private val driver: SqlDriver,
    private val embeddingModel: MemoryEmbeddingModel = DeterministicMemoryEmbeddingModel,
    private val json: Json = Json,
) : MemoryStore {
    private val queries get() = database.memoryQueries
    private val index = RebuildableInMemoryVectorIndex(DirectInferenceExecutor)
    private val loadedSessions = mutableSetOf<String>()
    private val loadMutex = Mutex()

    suspend fun write(entry: MemoryEntry, modelId: String): Set<String> {
        writeEntry(entry)
        queries.upsertEmbedding(
            memory_id = entry.id,
            model_id = modelId,
            semantic_json = json.encodeToString(entry.semanticEmbedding),
            emotional_json = json.encodeToString(entry.emotionalEmbedding),
            status = "READY",
        )
        try {
            index.insert(entry)
        } catch (_: Throwable) {
            index.markDirty()
        }
        return setOf(io.openeden.trace.TraceTag.MemoryWritten)
    }

    override suspend fun write(entry: MemoryEntry): Set<String> = write(entry, "unknown")

    suspend fun readById(id: String): StoredMemory? =
        queries.selectById(id, ::mapRow).executeAsOneOrNull()

    override suspend fun stableVectors(sessionId: String, limit: Int): List<BioVector> =
        queries.selectStable(sessionId, limit.toLong(), ::mapVector).executeAsList()

    override suspend fun retrieve(request: RetrievalRequest): RetrievalResult {
        ensureIndexed(request.sessionId)
        val candidates = index.search(
            VectorSearchRequest(
                sessionId = request.sessionId,
                semanticEmbedding = embeddingModel.embed(request.userInput),
                emotionalEmbedding = embeddingModel.embed(request.currentVector),
                limit = 128,
            ),
        ).map { it.entry }
        val palace = InMemoryMemoryPalace(DirectInferenceExecutor, embeddingModel = embeddingModel)
        candidates.forEach { palace.write(it) }
        return palace.retrieve(request)
    }

    fun close() = driver.close()

    private suspend fun ensureIndexed(sessionId: String) {
        loadMutex.withLock {
            if (sessionId in loadedSessions) return
            val entries = queries.selectBySession(sessionId, ::mapRow).executeAsList().map { it.entry }
            var indexed = true
            try {
                index.rebuild(entries)
            } catch (_: Throwable) {
                index.markDirty()
                indexed = false
            }
            if (indexed) loadedSessions += sessionId
        }
    }

    private fun writeEntry(entry: MemoryEntry) {
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
            created_at_ms = entry.id.substringAfterLast(':', "0").toLongOrNull() ?: 0L,
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
            ),
        )
        return StoredMemory(entry, modelId ?: "missing")
    }

    private fun mapVector(
        l: Double, p: Double, e: Double, s: Double, tau: Double, v: Double, m: Double, f: Double,
    ): BioVector = BioVector(l.toFloat(), p.toFloat(), e.toFloat(), s.toFloat(), tau.toFloat(), v.toFloat(), m.toFloat(), f.toFloat())

    companion object {
        fun open(
            dbPath: Path,
            embeddingModel: MemoryEmbeddingModel = DeterministicMemoryEmbeddingModel,
        ): SqlDelightMemoryRepository {
            dbPath.parent?.let { Files.createDirectories(it) }
            val driver = JdbcSqliteDriver("jdbc:sqlite:${dbPath.toAbsolutePath()}", Properties(), Database.Schema)
            return SqlDelightMemoryRepository(Database(driver), driver, embeddingModel)
        }
    }
}
