package io.openeden.memory

import io.openeden.bio.BioVector
import io.openeden.bio.VectorMapping
import io.openeden.runtime.InferenceExecutor
import io.openeden.trace.TraceTag
import kotlin.math.abs
import kotlin.math.sqrt

class InMemoryMemoryPalace(
    private val inferenceExecutor: InferenceExecutor,
    private val maxResults: Int = 6,
    private val embeddingModel: MemoryEmbeddingModel = DeterministicMemoryEmbeddingModel,
) : MemoryStore {
    private val entries = mutableListOf<MemoryEntry>()

    override suspend fun write(entry: MemoryEntry): Set<String> {
        entries += entry
        return setOf(TraceTag.MemoryWritten)
    }

    override suspend fun retrieve(request: RetrievalRequest): RetrievalResult =
        inferenceExecutor.run {
            val candidates = entries.filter { it.sessionId == request.sessionId }
            val selected = when (request.mode) {
                RetrievalMode.CONGRUENT -> rank(candidates, request, request.currentVector, maxResults)
                RetrievalMode.MIXED -> {
                    val positiveSkew = request.currentVector.copy(
                        p = (request.currentVector.p + 0.3f).coerceAtMost(1.0f),
                        v = (request.currentVector.v + 0.2f).coerceAtMost(1.0f),
                    )
                    val congruent = rank(candidates, request, request.currentVector, (maxResults * 0.6f).toInt().coerceAtLeast(1))
                    val skewed = rank(
                        candidates.filterNot { candidate -> congruent.any { it.id == candidate.id } },
                        request,
                        positiveSkew,
                        maxResults - congruent.size,
                    )
                    congruent + skewed
                }
                RetrievalMode.CONTRAST -> rank(
                    candidates = candidates,
                    request = request,
                    emotionalTarget = VectorMapping.centerSymmetricTarget(request.currentVector, request.origin),
                    limit = maxResults,
                )
            }
            RetrievalResult(
                mode = request.mode,
                injectionLabel = RetrievalModeSelector.injectionLabel(request.mode),
                memories = selected,
                traceTags = if (selected.isEmpty()) emptySet() else setOf(TraceTag.MemoryRetrieved),
            )
        }

    override suspend fun stableVectors(sessionId: String, limit: Int): List<BioVector> =
        inferenceExecutor.run {
            entries.asReversed()
                .asSequence()
                .filter { it.sessionId == sessionId && "daily" in it.tags && "stable" in it.tags }
                .take(limit)
                .map { it.metadata.snapshot8D }
                .toList()
        }

    private suspend fun rank(
        candidates: List<MemoryEntry>,
        request: RetrievalRequest,
        emotionalTarget: BioVector,
        limit: Int,
    ): List<MemorySnippet> {
        if (limit <= 0) return emptyList()
        val querySemantic = embeddingModel.embed(request.userInput)
        val queryEmotion = embeddingModel.embed(emotionalTarget)
        val beta = emotionalWeight(request.currentVector)
        val alpha = 1.0f - beta
        return candidates
            .asSequence()
            .map { entry ->
                val semantic = cosine(querySemantic, entry.semanticEmbedding)
                val emotion = cosine(queryEmotion, entry.emotionalEmbedding)
                val momentum = momentumImpact(entry.metadata)
                val score = alpha * semantic + beta * emotion + 0.15f * momentum
                MemorySnippet(
                    id = entry.id,
                    content = entry.content,
                    metadata = entry.metadata,
                    score = score,
                )
            }
            .sortedByDescending { it.score }
            .take(limit)
            .toList()
    }

    companion object {
        fun embedText(text: String, dimensions: Int = 16): List<Float> {
            val buckets = FloatArray(dimensions)
            for ((index, char) in text.withIndex()) {
                val bucket = (char.code + index).mod(dimensions)
                buckets[bucket] += 1.0f
            }
            return normalize(buckets)
        }

        fun embedVector(vector: BioVector): List<Float> = vector.toList()

        private fun emotionalWeight(vector: BioVector): Float {
            val stress = maxOf(vector.p, vector.s)
            return if (stress > 0.6f) 0.7f else 0.4f
        }

        private fun momentumImpact(metadata: MemoryMetadata): Float {
            val delta = metadata.deltaVec
            return (abs(delta.p) + abs(delta.v)).coerceIn(0.0f, 1.0f)
        }

        private fun normalize(values: FloatArray): List<Float> {
            var norm = 0.0f
            for (value in values) norm += value * value
            val denominator = sqrt(norm)
            if (denominator == 0.0f) return values.toList()
            return values.map { it / denominator }
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
}
