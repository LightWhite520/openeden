package io.openeden.memory

import io.openeden.runtime.InferenceExecutor
import io.openeden.runtime.DirectInferenceExecutor
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import kotlin.math.sqrt

class RebuildableInMemoryVectorIndex(
    private val inferenceExecutor: InferenceExecutor = DirectInferenceExecutor,
) : VectorIndex {
    private val mutex = Mutex()
    private val entries = mutableMapOf<String, MemoryEntry>()
    var isDirty: Boolean = false
        private set

    override suspend fun insert(entry: MemoryEntry) {
        mutex.withLock {
            entries[entry.id] = entry
            isDirty = false
        }
    }

    override suspend fun remove(memoryId: String) {
        mutex.withLock {
            entries.remove(memoryId)
            isDirty = false
        }
    }

    override suspend fun rebuild(entries: Iterable<MemoryEntry>, batchSize: Int) {
        val safeBatchSize = batchSize.coerceAtLeast(1)
        val rebuilt = mutableMapOf<String, MemoryEntry>()
        var count = 0
        for (entry in entries) {
            rebuilt[entry.id] = entry
            count += 1
            if (count % safeBatchSize == 0) yield()
        }
        mutex.withLock {
            this.entries.clear()
            this.entries.putAll(rebuilt)
            isDirty = false
        }
    }

    override suspend fun markDirty() {
        mutex.withLock { isDirty = true }
    }

    override suspend fun search(request: VectorSearchRequest): List<VectorSearchHit> =
        inferenceExecutor.run {
            val snapshot = mutex.withLock {
                entries.values.filter { entry ->
                    entry.sessionId == request.sessionId &&
                        (request.room == null || entry.room == request.room) &&
                        (request.kind == null || entry.kind == request.kind)
                }.toList()
            }
            snapshot.asSequence()
                .map { entry ->
                    VectorSearchHit(
                        entry = entry,
                        semanticSimilarity = cosine(request.semanticEmbedding, entry.semanticEmbedding),
                        emotionalSimilarity = request.emotionalEmbedding?.let {
                            cosine(it, entry.emotionalEmbedding)
                        } ?: 0.0f,
                    )
                }
                .sortedByDescending { it.semanticSimilarity }
                .take(request.limit.coerceAtLeast(0))
                .toList()
        }

    private fun cosine(left: List<Float>, right: List<Float>): Float {
        val size = minOf(left.size, right.size)
        if (size == 0) return 0.0f
        var dot = 0.0f
        var leftNorm = 0.0f
        var rightNorm = 0.0f
        for (index in 0 until size) {
            dot += left[index] * right[index]
            leftNorm += left[index] * left[index]
            rightNorm += right[index] * right[index]
        }
        val denominator = sqrt(leftNorm) * sqrt(rightNorm)
        return if (denominator == 0.0f) 0.0f else dot / denominator
    }
}
