package io.openeden.server.vector

data class VectorDatabaseStatus(
    val backend: String = "QDRANT",
    val collection: String? = null,
    val circuit: QdrantCircuitBreaker.Snapshot,
    val fallbackActive: Boolean,
    val lastTraceTag: String,
    val lastErrorCategory: String? = null,
    val lastErrorAtMs: Long? = null,
)
