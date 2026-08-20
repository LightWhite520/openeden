package io.openeden.server.vector.qdrant

data class QdrantPoint(val id: String, val vector: FloatArray, val payload: Map<String, String> = emptyMap())

data class QdrantFilter(val must: List<QdrantFieldCondition> = emptyList())

data class QdrantFieldCondition(val key: String, val value: String)

data class QdrantCollection(val status: String? = null)

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
