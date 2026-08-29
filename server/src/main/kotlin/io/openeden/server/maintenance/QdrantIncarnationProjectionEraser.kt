package io.openeden.server.maintenance

import io.openeden.server.vector.qdrant.QdrantClient
import io.openeden.server.vector.qdrant.QdrantClientException
import io.openeden.server.vector.qdrant.QdrantErrorCategory
import io.openeden.server.vector.qdrant.QdrantFieldCondition
import io.openeden.server.vector.qdrant.QdrantFilter
import io.openeden.server.vector.qdrant.QdrantCollectionNaming

class QdrantIncarnationProjectionEraser(
    private val client: QdrantClient,
    private val naming: QdrantCollectionNaming,
    private val configuredModelId: String,
) : IncarnationProjectionEraser {
    init {
        require(configuredModelId.isNotBlank()) { "Qdrant modelId must not be blank" }
    }

    override suspend fun eraseAndVerify(incarnationId: String, modelIds: Set<String>) {
        require(incarnationId.isNotBlank()) { "incarnationId must not be blank" }
        val authoritativeModels = (modelIds + configuredModelId).onEach { modelId ->
            if (modelId.isBlank()) throw IncarnationProjectionConfigurationException("Blank persisted projection model")
        }
        authoritativeModels.sorted().forEach { modelId ->
            val collection = naming.collectionName(modelId)
            val filter = QdrantFilter(
                must = listOf(
                    QdrantFieldCondition("incarnation_id", incarnationId),
                    QdrantFieldCondition("model_id", modelId),
                ),
            )
            try {
                client.deletePoints(collection, filter, wait = true)
                check(client.countPoints(collection, filter) == 0L) {
                    "Qdrant projection erase verification failed for collection=$collection model=$modelId"
                }
            } catch (failure: QdrantClientException) {
                if (failure.category != QdrantErrorCategory.HTTP || failure.statusCode != 404) throw failure
            }
        }
    }
}
