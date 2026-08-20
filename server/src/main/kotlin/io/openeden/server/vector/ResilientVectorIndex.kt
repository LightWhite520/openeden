package io.openeden.server.vector

import io.openeden.memory.MemoryEntry
import io.openeden.memory.RebuildableInMemoryVectorIndex
import io.openeden.memory.VectorIndex
import io.openeden.memory.VectorSearchHit
import io.openeden.memory.VectorSearchRequest
import io.openeden.server.vector.qdrant.QdrantClientException
import io.openeden.trace.TraceTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ResilientVectorIndex(
    private val primary: VectorIndex,
    private val fallback: RebuildableInMemoryVectorIndex = RebuildableInMemoryVectorIndex(),
    private val circuit: QdrantCircuitBreaker = QdrantCircuitBreaker(),
    private val collection: String? = null,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) : VectorIndex {
    private val operationMutex = Mutex()
    private val statusMutex = Mutex()
    private var fallbackActive = false
    private var lastTraceTag = TRACE_QDRANT
    private var lastErrorCategory: String? = null
    private var lastErrorAtMs: Long? = null

    override suspend fun insert(entry: MemoryEntry) {
        operationMutex.withLock {
            fallback.insert(entry)
            runPrimary { primary.insert(entry) }
        }
    }

    override suspend fun remove(memoryId: String) {
        operationMutex.withLock {
            fallback.remove(memoryId)
            runPrimary { primary.remove(memoryId) }
        }
    }

    override suspend fun rebuild(entries: Iterable<MemoryEntry>, batchSize: Int) {
        operationMutex.withLock {
            fallback.rebuild(entries, batchSize)
            val replay = fallback.entriesViewForRebuild()
            runPrimary { primary.rebuild(replay, batchSize) }
        }
    }

    override suspend fun search(request: VectorSearchRequest): List<VectorSearchHit> {
        return try {
            val result = circuit.execute { primary.search(request) }
            if (result == null) {
                markFallback(null)
                fallback.search(request)
            } else {
                markRemoteSuccess()
                result
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            markFallback(failure)
            fallback.search(request)
        }
    }

    override suspend fun markDirty() {
        operationMutex.withLock {
            fallback.markDirty()
            try {
                primary.markDirty()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                markFallback(failure)
            }
        }
    }

    suspend fun status(): VectorDatabaseStatus = statusMutex.withLock {
        VectorDatabaseStatus(
            collection = collection,
            circuit = circuit.snapshot(),
            fallbackActive = fallbackActive,
            lastTraceTag = lastTraceTag,
            lastErrorCategory = lastErrorCategory,
            lastErrorAtMs = lastErrorAtMs,
        )
    }

    private suspend fun runPrimary(operation: suspend () -> Unit) {
        try {
            val result = circuit.execute { operation(); true }
            if (result == null) markFallback(null) else markRemoteSuccess()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            markFallback(failure)
        }
    }

    private suspend fun markRemoteSuccess() = statusMutex.withLock {
        val recovered = fallbackActive
        fallbackActive = false
        lastTraceTag = if (recovered) TRACE_RECOVERED else TRACE_QDRANT
        lastErrorCategory = null
    }

    private suspend fun markFallback(failure: Throwable?) = statusMutex.withLock {
        fallbackActive = true
        lastTraceTag = TRACE_FALLBACK
        lastErrorCategory = failure?.let { safeErrorCategory(it) }
        lastErrorAtMs = failure?.let { nowMs() }
    }

    private fun safeErrorCategory(failure: Throwable): String =
        (failure as? QdrantClientException)?.category?.name
            ?: failure::class.simpleName.orEmpty().ifBlank { "REMOTE_FAILURE" }

    companion object {
        const val TRACE_QDRANT = TraceTag.VectorDatabaseQdrant
        const val TRACE_FALLBACK = TraceTag.VectorDatabaseFallback
        const val TRACE_RECOVERED = TraceTag.VectorDatabaseRecovered
    }
}
