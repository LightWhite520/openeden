package io.openeden.server.vector.qdrant

import io.openeden.memory.MemoryEntry
import io.openeden.memory.VectorIndex
import io.openeden.memory.VectorSearchHit
import io.openeden.memory.VectorSearchRequest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class QdrantVectorIndex(
    private val client: QdrantClient,
    private val naming: QdrantCollectionNaming,
    private val modelId: String,
    private val maxSearchLimit: Int = MAX_SEARCH_LIMIT,
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
            client.upsertPoints(collection, listOf(entry.toPoint(modelId)))
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
            val iterator = entries.iterator()
            if (!iterator.hasNext()) {
                if (ensureExistingCollectionLocked()) client.deletePoints(collection, activeModelFilter())
                return
            }

            var expectedDimensions = dimensions
            val first = iterator.next()
            validateVectors(first.semanticEmbedding, first.emotionalEmbedding, expectedDimensions)
            val firstDimensions = Dimensions(first.semanticEmbedding.size, first.emotionalEmbedding.size)
            expectedDimensions = expectedDimensions ?: firstDimensions
            require(expectedDimensions == firstDimensions) {
                incompatibleMessage(expectedDimensions, firstDimensions)
            }
            val establishedDimensions = requireNotNull(expectedDimensions)
            ensureCollectionLocked(establishedDimensions.semantic, establishedDimensions.emotional)
            client.deletePoints(collection, activeModelFilter())

            val batch = ArrayList<QdrantPoint>(batchSize)
            batch += first.toPoint(modelId)
            if (batch.size == batchSize) {
                client.upsertPoints(collection, batch.toList())
                batch.clear()
            }
            while (iterator.hasNext()) {
                val entry = iterator.next()
                validateVectors(entry.semanticEmbedding, entry.emotionalEmbedding, expectedDimensions)
                val entryDimensions = Dimensions(entry.semanticEmbedding.size, entry.emotionalEmbedding.size)
                require(expectedDimensions == entryDimensions) {
                    incompatibleMessage(expectedDimensions, entryDimensions)
                }
                batch += entry.toPoint(modelId)
                if (batch.size == batchSize) {
                    client.upsertPoints(collection, batch.toList())
                    batch.clear()
                }
            }
            if (batch.isNotEmpty()) client.upsertPoints(collection, batch.toList())
        }
    }

    override suspend fun search(request: VectorSearchRequest): List<VectorSearchHit> {
        val limit = request.limit.coerceIn(0, maxSearchLimit)
        if (limit == 0) return emptyList()
        validateVector(request.semanticEmbedding, "semantic", dimensions?.semantic)
        val knownCollection = ensureSearchCollection(request.semanticEmbedding.size) ?: return emptyList()
        val filter = QdrantFilter(
            must = buildList {
                add(QdrantFieldCondition("session_id", request.sessionId))
                request.room?.let { add(QdrantFieldCondition("room", it.name)) }
                request.kind?.let { add(QdrantFieldCondition("kind", it.name)) }
                add(QdrantFieldCondition("model_id", modelId))
            },
        )
        return client.searchSemanticPoints(knownCollection, request.semanticEmbedding.toFloatArray(), limit, filter)
            .mapNotNull { hit ->
                val memoryId = hit.payload[MEMORY_ID]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                VectorSearchHit(memoryId, entry = null, semanticSimilarity = hit.score.toFloat(), emotionalSimilarity = 0.0f)
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
        } else {
            validateCollection(inspected, expected)
        }
        ensurePayloadIndexes()
        dimensions = expected
        collectionReady = true
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

    private fun incompatibleMessage(actual: Dimensions, expected: Dimensions) =
        "vector dimensions $expected do not match established dimensions $actual"

    private data class Dimensions(val semantic: Int, val emotional: Int) {
        fun asSpecs() = mapOf(
            SEMANTIC to QdrantVectorSpec(semantic, COSINE),
            EMOTIONAL to QdrantVectorSpec(emotional, COSINE),
        )
    }

    private companion object {
        const val SEMANTIC = "semantic"
        const val EMOTIONAL = "emotional"
        const val MEMORY_ID = "memory_id"
        const val COSINE = "Cosine"
        const val MAX_SEARCH_LIMIT = 128
        val PAYLOAD_INDEXES = listOf("session_id", "room", "kind", "model_id")
    }
}

private fun MemoryEntry.toPoint(modelId: String): QdrantPoint = QdrantPoint(
    id = QdrantPointIds.fromMemoryId(id),
    vectors = mapOf("semantic" to semanticEmbedding.toFloatArray(), "emotional" to emotionalEmbedding.toFloatArray()),
    payload = mapOf(
        "memory_id" to id,
        "session_id" to sessionId,
        "room" to room.name,
        "kind" to kind.name,
        "model_id" to modelId,
    ),
)
