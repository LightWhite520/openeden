package io.openeden.server.vector.qdrant

import io.openeden.memory.MemoryEntry
import io.openeden.memory.VectorIndex
import io.openeden.memory.VectorSearchHit
import io.openeden.memory.VectorSearchRequest
import io.openeden.trace.TraceTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path

class QdrantVectorIndex(
    private val client: QdrantClient,
    private val naming: QdrantCollectionNaming,
    private val modelId: String,
    private val maxSearchLimit: Int = MAX_SEARCH_LIMIT,
    private val onCollectionRecreated: (suspend () -> Unit)? = null,
    private val onTrace: (String) -> Unit = {},
) : VectorIndex {
    private val collection = naming.collectionName(modelId)
    private val stateMutex = Mutex()
    private var dimensions: Dimensions? = null
    private var collectionReady = false

    init {
        require(modelId.isNotBlank()) { "modelId must not be blank" }
        require(maxSearchLimit > 0) { "maxSearchLimit must be positive" }
    }

    override suspend fun insert(entry: MemoryEntry) {
        stateMutex.withLock {
            validateVectors(entry.semanticEmbedding, entry.emotionalEmbedding, dimensions)
            ensureCollectionLocked(entry.semanticEmbedding.size, entry.emotionalEmbedding.size)
            try {
                client.upsertPoints(collection, listOf(entry.toPoint(modelId)))
            } catch (failure: QdrantClientException) {
                resetIfCollectionMissing(failure)
                throw failure
            }
        }
    }

    override suspend fun remove(memoryId: String) {
        stateMutex.withLock {
            try {
                client.deletePoints(collection, listOf(QdrantPointIds.fromMemoryId(memoryId)))
            } catch (failure: QdrantClientException) {
                if (failure.category != QdrantErrorCategory.HTTP || failure.statusCode != 404) throw failure
            }
        }
    }

    override suspend fun rebuild(entries: Iterable<MemoryEntry>, batchSize: Int) {
        require(batchSize > 0) { "batchSize must be positive" }
        stateMutex.withLock {
            val snapshot = withContext(Dispatchers.IO) {
                Files.createTempFile("openeden-qdrant-rebuild-", ".bin")
            }
            try {
                val summary = writeSnapshot(snapshot, entries)
                if (summary.count == 0) {
                    if (ensureExistingCollectionLocked()) client.deletePoints(collection, activeModelFilter())
                } else {
                    val establishedDimensions = requireNotNull(summary.dimensions)
                    ensureCollectionLocked(establishedDimensions.semantic, establishedDimensions.emotional)
                    client.deletePoints(collection, activeModelFilter())
                    replaySnapshot(snapshot, summary.count, batchSize)
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { Files.deleteIfExists(snapshot) }
            }
        }
    }

    override suspend fun search(request: VectorSearchRequest): List<VectorSearchHit> {
        val limit = request.limit.coerceIn(0, maxSearchLimit)
        if (limit == 0) return emptyList()
        validateVector(request.semanticEmbedding, "semantic", dimensions?.semantic)
        val knownCollection = ensureSearchCollection(request.semanticEmbedding.size) ?: return emptyList()
        request.emotionalEmbedding?.let { validateVector(it, EMOTIONAL, dimensions?.emotional) }
        val filter = QdrantFilter(
            must = buildList {
                request.incarnationId.takeIf { it.isNotBlank() }
                    ?.let { add(QdrantFieldCondition("incarnation_id", it)) }
                    ?: add(QdrantFieldCondition("session_id", request.sessionId))
                request.room?.let { add(QdrantFieldCondition("room", it.name)) }
                request.kind?.let { add(QdrantFieldCondition("kind", it.name)) }
                add(QdrantFieldCondition("model_id", modelId))
            },
            should = request.authorizedVisibilityKeys(),
        )
        return try {
            coroutineScope {
                val semanticHits = async {
                    client.searchSemanticPoints(
                        knownCollection,
                        request.semanticEmbedding.toFloatArray(),
                        limit,
                        filter,
                        using = SEMANTIC,
                    )
                }
                val emotionalHits = request.emotionalEmbedding?.let { emotionalEmbedding ->
                    async {
                        client.searchSemanticPoints(
                            knownCollection,
                            emotionalEmbedding.toFloatArray(),
                            limit,
                            filter,
                            using = EMOTIONAL,
                        )
                    }
                }
                val merged = linkedMapOf<String, VectorSearchHit>()
                semanticHits.await().forEach { hit ->
                    val memoryId = hit.payload[MEMORY_ID]?.takeIf { it.isNotBlank() } ?: return@forEach
                    merged[memoryId] = VectorSearchHit(
                        memoryId,
                        entry = null,
                        semanticSimilarity = hit.score.toFloat(),
                        emotionalSimilarity = 0.0f,
                    )
                }
                emotionalHits?.await()?.forEach { hit ->
                    val memoryId = hit.payload[MEMORY_ID]?.takeIf { it.isNotBlank() } ?: return@forEach
                    val existing = merged[memoryId]
                    merged[memoryId] = if (existing == null) {
                        VectorSearchHit(
                            memoryId,
                            entry = null,
                            semanticSimilarity = 0.0f,
                            emotionalSimilarity = hit.score.toFloat(),
                        )
                    } else {
                        existing.copy(emotionalSimilarity = hit.score.toFloat())
                    }
                }
                merged.values.toList()
            }
        } catch (failure: QdrantClientException) {
            stateMutex.withLock { resetIfCollectionMissing(failure) }
            throw failure
        }
    }

    override suspend fun markDirty() {
        stateMutex.withLock {
            collectionReady = false
            dimensions = null
        }
    }

    private suspend fun ensureCollectionLocked(semanticSize: Int, emotionalSize: Int) {
        val expected = Dimensions(semanticSize, emotionalSize)
        dimensions?.let { existing -> require(existing == expected) { incompatibleMessage(existing, expected) } }
        if (collectionReady) return
        val inspected = client.inspectCollection(collection)
        if (inspected == null) {
            client.createCollection(collection, expected.asSpecs())
            onCollectionRecreated?.invoke()
            emitTrace(TraceTag.VectorCollectionCreated)
        } else {
            validateCollection(inspected, expected)
        }
        ensurePayloadIndexes()
        dimensions = expected
        collectionReady = true
    }

    private fun emitTrace(tag: String) {
        runCatching { onTrace(tag) }
    }

    private suspend fun ensureExistingCollectionLocked(): Boolean {
        if (collectionReady) return true
        val inspected = client.inspectCollection(collection) ?: return false
        val inspectedDimensions = inspected.vectors.toDimensions()
        require(inspectedDimensions != null) { "Qdrant collection is missing named vectors" }
        validateCollection(inspected, inspectedDimensions)
        ensurePayloadIndexes()
        dimensions = inspectedDimensions
        collectionReady = true
        return true
    }

    private suspend fun writeSnapshot(path: Path, entries: Iterable<MemoryEntry>): SnapshotSummary =
        withContext(Dispatchers.IO) {
            DataOutputStream(BufferedOutputStream(Files.newOutputStream(path))).use { output ->
                var expectedDimensions = dimensions
                var count = 0
                for (entry in entries) {
                    validateVectors(entry.semanticEmbedding, entry.emotionalEmbedding, expectedDimensions)
                    val entryDimensions = Dimensions(entry.semanticEmbedding.size, entry.emotionalEmbedding.size)
                    expectedDimensions = expectedDimensions ?: entryDimensions
                    require(expectedDimensions == entryDimensions) {
                        incompatibleMessage(expectedDimensions, entryDimensions)
                    }
                    writePoint(output, entry.toPoint(modelId))
                    count += 1
                }
                SnapshotSummary(count, expectedDimensions)
            }
        }

    private suspend fun replaySnapshot(path: Path, count: Int, batchSize: Int) {
        val input = withContext(Dispatchers.IO) {
            DataInputStream(BufferedInputStream(Files.newInputStream(path)))
        }
        try {
            var remaining = count
            while (remaining > 0) {
                val batchCount = minOf(remaining, batchSize)
                val batch = withContext(Dispatchers.IO) {
                    ArrayList<QdrantPoint>(batchCount).also { points ->
                        repeat(batchCount) { points += readPoint(input) }
                    }
                }
                client.upsertPoints(collection, batch)
                remaining -= batchCount
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { input.close() }
        }
    }

    private fun writePoint(output: DataOutputStream, point: QdrantPoint) {
        output.writeUTF(point.id)
        output.writeInt(point.vectors.size)
        point.vectors.toSortedMap().forEach { (name, vector) ->
            output.writeUTF(name)
            output.writeInt(vector.size)
            vector.forEach(output::writeFloat)
        }
        output.writeInt(point.payload.size)
        point.payload.toSortedMap().forEach { (key, value) ->
            output.writeUTF(key)
            output.writeUTF(value)
        }
    }

    private fun readPoint(input: DataInputStream): QdrantPoint {
        val id = input.readUTF()
        val vectorCount = input.readInt().also { require(it >= 0) { "snapshot vector count must not be negative" } }
        val vectors = buildMap {
            repeat(vectorCount) {
                val name = input.readUTF()
                val size = input.readInt().also { require(it >= 0) { "snapshot vector size must not be negative" } }
                put(name, FloatArray(size) { input.readFloat() })
            }
        }
        val payloadCount = input.readInt().also { require(it >= 0) { "snapshot payload count must not be negative" } }
        val payload = buildMap {
            repeat(payloadCount) { put(input.readUTF(), input.readUTF()) }
        }
        return QdrantPoint(id, vectors, payload)
    }

    private suspend fun ensureSearchCollection(semanticSize: Int): String? {
        stateMutex.withLock {
            dimensions?.let { existing -> validateVectorSize(semanticSize, existing.semantic, "semantic") }
            if (!collectionReady) {
                val inspected = client.inspectCollection(collection) ?: return null
                val inspectedDimensions = inspected.vectors.toDimensions()
                require(inspectedDimensions != null) { "Qdrant collection is missing named vectors" }
                validateCollection(inspected, inspectedDimensions)
                ensurePayloadIndexes()
                dimensions = inspectedDimensions
                collectionReady = true
            }
            dimensions?.let { existing -> validateVectorSize(semanticSize, existing.semantic, "semantic") }
            return collection
        }
    }

    private fun validateCollection(collection: QdrantCollection, expected: Dimensions) {
        val actualSemantic = collection.vectors[SEMANTIC]
            ?: throw IllegalArgumentException("Qdrant collection is missing semantic vector")
        val actualEmotional = collection.vectors[EMOTIONAL]
            ?: throw IllegalArgumentException("Qdrant collection is missing emotional vector")
        require(actualSemantic.distance.equals(COSINE, ignoreCase = true) && actualEmotional.distance.equals(COSINE, ignoreCase = true)) {
            "Qdrant named vectors must use Cosine distance"
        }
        require(actualSemantic.size == expected.semantic && actualEmotional.size == expected.emotional) {
            "Qdrant collection dimensions are incompatible"
        }
    }

    private fun Map<String, QdrantVectorSpec>.toDimensions(): Dimensions? {
        val semantic = this[SEMANTIC] ?: return null
        val emotional = this[EMOTIONAL] ?: return null
        return Dimensions(semantic.size, emotional.size)
    }

    private suspend fun ensurePayloadIndexes() {
        PAYLOAD_INDEXES.forEach { field -> client.ensurePayloadIndex(collection, field) }
    }

    private fun activeModelFilter() = QdrantFilter(
        must = listOf(QdrantFieldCondition("model_id", modelId)),
    )

    private fun validateVectors(semantic: List<Float>, emotional: List<Float>, known: Dimensions?) {
        validateVector(semantic, SEMANTIC, known?.semantic)
        validateVector(emotional, EMOTIONAL, known?.emotional)
    }

    private fun validateVector(vector: List<Float>, name: String, expectedSize: Int?) {
        require(vector.isNotEmpty()) { "$name vector must not be empty" }
        require(vector.all(Float::isFinite)) { "$name vector must contain only finite values" }
        require(vector.any { it != 0.0f }) { "$name vector must not be all zero" }
        expectedSize?.let { validateVectorSize(vector.size, it, name) }
    }

    private fun validateVectorSize(actual: Int, expected: Int, name: String) {
        require(actual == expected) { "$name vector dimension $actual does not match expected $expected" }
    }

    private fun resetIfCollectionMissing(failure: QdrantClientException) {
        if (failure.statusCode == 404) {
            collectionReady = false
            dimensions = null
        }
    }

    private fun incompatibleMessage(actual: Dimensions, expected: Dimensions) =
        "vector dimensions $expected do not match established dimensions $actual"

    private data class Dimensions(val semantic: Int, val emotional: Int) {
        fun asSpecs() = mapOf(
            SEMANTIC to QdrantVectorSpec(semantic, COSINE),
            EMOTIONAL to QdrantVectorSpec(emotional, COSINE),
        )
    }

    private data class SnapshotSummary(val count: Int, val dimensions: Dimensions?)

    private companion object {
        const val SEMANTIC = "semantic"
        const val EMOTIONAL = "emotional"
        const val MEMORY_ID = "memory_id"
        const val COSINE = "Cosine"
        const val MAX_SEARCH_LIMIT = 128
        val PAYLOAD_INDEXES = listOf("session_id", "incarnation_id", "room", "kind", "model_id", "visibility_key")
    }
}

private fun MemoryEntry.toPoint(modelId: String): QdrantPoint = QdrantPoint(
    id = QdrantPointIds.fromMemoryId(id),
    vectors = mapOf("semantic" to semanticEmbedding.toFloatArray(), "emotional" to emotionalEmbedding.toFloatArray()),
    payload = mapOf(
        "memory_id" to id,
        "session_id" to sessionId,
        "incarnation_id" to metadata.incarnationId,
        "room" to room.name,
        "kind" to kind.name,
        "model_id" to modelId,
        "visibility_key" to metadata.visibility.accessKey(metadata.incarnationId),
    ),
)

private fun VectorSearchRequest.authorizedVisibilityKeys(): List<QdrantFieldCondition> = buildList {
    add(QdrantFieldCondition("visibility_key", "scope:$sessionId"))
    canonicalSubjectId.takeIf { it.isNotBlank() }?.let { subjectId ->
        add(QdrantFieldCondition("visibility_key", "subject:$subjectId"))
    }
    incarnationId.takeIf { it.isNotBlank() }?.let { id ->
        add(QdrantFieldCondition("visibility_key", "incarnation:$id"))
    }
}

private fun io.openeden.memory.MemoryVisibility.accessKey(incarnationId: String): String = when (this) {
    is io.openeden.memory.MemoryVisibility.PrivateSubject -> "subject:$subjectId"
    is io.openeden.memory.MemoryVisibility.ScopeShared -> "scope:$sessionId"
    io.openeden.memory.MemoryVisibility.IncarnationShared -> "incarnation:$incarnationId"
    io.openeden.memory.MemoryVisibility.OperatorOnly -> "operator"
}
