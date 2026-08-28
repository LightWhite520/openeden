package io.openeden.memory

import io.openeden.runtime.inference.InferenceExecutor
import io.openeden.runtime.inference.DirectInferenceExecutor
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import kotlin.math.sqrt

class RebuildableInMemoryVectorIndex(
    private val inferenceExecutor: InferenceExecutor = DirectInferenceExecutor,
) : VectorIndex {
    private val mutex = Mutex()
    private val entries = mutableMapOf<String, MemoryEntry>()
    private var publishedEntries: List<MemoryEntry> = emptyList()
    var isDirty: Boolean = false
        private set

    override suspend fun insert(entry: MemoryEntry) {
        mutex.withLock {
            entries[entry.id] = entry
            publishedEntries = entries.values.toList()
            isDirty = false
        }
    }

    override suspend fun remove(memoryId: String) {
        mutex.withLock {
            if (entries.remove(memoryId) != null) {
                publishedEntries = entries.values.toList()
            }
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
            publishedEntries = this.entries.values.toList()
            isDirty = false
        }
    }

    override suspend fun markDirty() {
        mutex.withLock { isDirty = true }
    }

    suspend fun entriesViewForRebuild(): Iterable<MemoryEntry> = mutex.withLock { publishedEntries }

    override suspend fun search(request: VectorSearchRequest): List<VectorSearchHit> =
        inferenceExecutor.run {
            if (request.incarnationId.isBlank()) return@run emptyList()
            val limit = request.limit.coerceAtLeast(0)
            if (limit == 0) return@run emptyList()
            val snapshot = mutex.withLock { publishedEntries }
            val ranking = BoundedSearchRanking(limit)
            for ((order, entry) in snapshot.withIndex()) {
                if (
                    entry.metadata.incarnationId != request.incarnationId ||
                    !entry.isVisibleTo(request) ||
                    (request.room != null && entry.room != request.room) ||
                    (request.kind != null && entry.kind != request.kind)
                ) {
                    continue
                }
                val semanticSimilarity = cosine(request.semanticEmbedding, entry.semanticEmbedding)
                if (!ranking.wouldRetain(semanticSimilarity, order)) continue
                val emotionalSimilarity = request.emotionalEmbedding?.let {
                    cosine(it, entry.emotionalEmbedding)
                } ?: 0.0f
                ranking.offer(entry, semanticSimilarity, emotionalSimilarity, order)
            }
            ranking.results()
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

private class BoundedSearchRanking(private val limit: Int) {
    private val heap = ArrayList<RankedSearchHit>(limit)

    fun wouldRetain(semanticSimilarity: Float, order: Int): Boolean =
        heap.size < limit || compareBestFirst(semanticSimilarity, order, heap[0]) < 0

    fun offer(
        entry: MemoryEntry,
        semanticSimilarity: Float,
        emotionalSimilarity: Float,
        order: Int,
    ) {
        if (heap.size < limit) {
            heap += RankedSearchHit(entry, semanticSimilarity, emotionalSimilarity, order)
            siftUp(heap.lastIndex)
            return
        }
        if (compareBestFirst(semanticSimilarity, order, heap[0]) >= 0) return
        heap[0].set(entry, semanticSimilarity, emotionalSimilarity, order)
        siftDown(0)
    }

    fun results(): List<VectorSearchHit> = heap
        .sortedWith(::compareBestFirst)
        .map { ranked ->
            VectorSearchHit(
                memoryId = ranked.entry.id,
                entry = ranked.entry,
                semanticSimilarity = ranked.semanticSimilarity,
                emotionalSimilarity = ranked.emotionalSimilarity,
            )
        }

    private fun siftUp(startIndex: Int) {
        var child = startIndex
        while (child > 0) {
            val parent = (child - 1) / 2
            if (!isWorse(heap[child], heap[parent])) return
            heap.swap(child, parent)
            child = parent
        }
    }

    private fun siftDown(startIndex: Int) {
        var parent = startIndex
        while (true) {
            val left = parent * 2 + 1
            if (left >= heap.size) return
            val right = left + 1
            val worseChild = if (right < heap.size && isWorse(heap[right], heap[left])) right else left
            if (!isWorse(heap[worseChild], heap[parent])) return
            heap.swap(parent, worseChild)
            parent = worseChild
        }
    }

    private fun isWorse(left: RankedSearchHit, right: RankedSearchHit): Boolean =
        compareBestFirst(left, right) > 0

    private fun compareBestFirst(left: RankedSearchHit, right: RankedSearchHit): Int =
        compareBestFirst(left.semanticSimilarity, left.order, right)

    private fun compareBestFirst(
        semanticSimilarity: Float,
        order: Int,
        right: RankedSearchHit,
    ): Int {
        val scoreComparison = right.semanticSimilarity.compareTo(semanticSimilarity)
        return if (scoreComparison != 0) scoreComparison else order.compareTo(right.order)
    }
}

private class RankedSearchHit(
    var entry: MemoryEntry,
    var semanticSimilarity: Float,
    var emotionalSimilarity: Float,
    var order: Int,
) {
    fun set(
        entry: MemoryEntry,
        semanticSimilarity: Float,
        emotionalSimilarity: Float,
        order: Int,
    ) {
        this.entry = entry
        this.semanticSimilarity = semanticSimilarity
        this.emotionalSimilarity = emotionalSimilarity
        this.order = order
    }
}

private fun <T> MutableList<T>.swap(left: Int, right: Int) {
    val value = this[left]
    this[left] = this[right]
    this[right] = value
}
