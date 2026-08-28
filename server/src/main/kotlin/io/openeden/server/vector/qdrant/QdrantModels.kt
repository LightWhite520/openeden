package io.openeden.server.vector.qdrant

data class QdrantVectorSpec(val size: Int, val distance: String = "Cosine")

data class QdrantPoint(val id: String, val vectors: Map<String, FloatArray>, val payload: Map<String, String> = emptyMap())

data class QdrantFilter(
    val must: List<QdrantFieldCondition> = emptyList(),
    val should: List<QdrantFieldCondition> = emptyList(),
)

data class QdrantFieldCondition(val key: String, val value: String)

data class QdrantCollection(val status: String? = null, val vectors: Map<String, QdrantVectorSpec> = emptyMap())

data class QdrantSearchHit(val id: String, val score: Double, val payload: Map<String, String> = emptyMap())

enum class QdrantErrorCategory { HTTP, MALFORMED_JSON, TIMEOUT, NETWORK }

class QdrantClientException(
    val category: QdrantErrorCategory,
    val statusCode: Int? = null,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message.take(MAX_ERROR_LENGTH), cause) {
    private companion object { const val MAX_ERROR_LENGTH = 256 }
}
