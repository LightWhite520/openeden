package io.openeden.server.vector

data class VectorDatabaseStatus(
    val backend: String = "QDRANT",
    val collection: String? = null,
    val circuit: QdrantCircuitBreaker.Snapshot,
    val fallbackActive: Boolean,
    val lastTraceTag: String,
    val pendingProjectionCount: Long = 0L,
    val totalNonReadyProjectionCount: Long = 0L,
    val lastErrorCategory: String? = null,
    val lastErrorAtMs: Long? = null,
)

fun interface VectorDatabaseStatusProvider {
    suspend fun snapshot(): VectorDatabaseStatus
}
