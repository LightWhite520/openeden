package io.openeden.memory

import kotlin.math.ln
import kotlin.math.sqrt

data class MemoryUtilityFilterResult(
    val candidates: List<MemoryEntry>,
    val acceptedCount: Int,
    val rejectedCount: Int,
    val degraded: Boolean,
    val rejectedForEntropy: Boolean,
)

object MemoryUtilityFilter {
    fun filter(
        candidates: List<MemoryEntry>,
        querySemantic: List<Float>,
        queryEmotion: List<Float>,
        baselineEntropy: Float?,
        config: MemoryUtilityFilterConfig = MemoryUtilityFilterConfig(),
    ): MemoryUtilityFilterResult {
        val queryIsFinite = querySemantic.all(Float::isFinite) && queryEmotion.all(Float::isFinite)
        val baselineIsFinite = baselineEntropy == null || baselineEntropy.isFinite()
        val degraded = !queryIsFinite || !baselineIsFinite
        val accepted = mutableListOf<MemoryEntry>()
        var rejectedForEntropy = false

        for (candidate in candidates) {
            val finiteEmbeddings = candidate.semanticEmbedding.all(Float::isFinite) &&
                candidate.emotionalEmbedding.all(Float::isFinite)
            if (!finiteEmbeddings) continue

            if (!queryIsFinite) {
                accepted += candidate
                continue
            }

            val semanticSimilarity = cosine(querySemantic, candidate.semanticEmbedding)
            val emotionalSimilarity = cosine(queryEmotion, candidate.emotionalEmbedding)
            if (
                semanticSimilarity < config.minSemanticSimilarity &&
                emotionalSimilarity < config.minEmotionalSimilarity
            ) {
                continue
            }
            if (baselineIsFinite && baselineEntropy != null) {
                val candidateEntropy = meanEmbeddingEntropy(candidate)
                if (candidateEntropy > baselineEntropy + config.entropyTolerance) {
                    rejectedForEntropy = true
                    continue
                }
            }
            accepted += candidate
        }

        return MemoryUtilityFilterResult(
            candidates = accepted,
            acceptedCount = accepted.size,
            rejectedCount = candidates.size - accepted.size,
            degraded = degraded,
            rejectedForEntropy = rejectedForEntropy,
        )
    }

    fun meanEmbeddingEntropy(entry: MemoryEntry): Float =
        (embeddingEntropy(entry.semanticEmbedding) + embeddingEntropy(entry.emotionalEmbedding)) / 2.0f

    fun embeddingEntropy(embedding: List<Float>): Float {
        if (embedding.isEmpty() || embedding.any { !it.isFinite() }) return Float.NaN
        val magnitudes = embedding.map { kotlin.math.abs(it) }
        val total = magnitudes.sum()
        if (total == 0.0f) return 0.0f
        return magnitudes.sumOf { magnitude ->
            if (magnitude == 0.0f) 0.0 else {
                val probability = magnitude / total
                -probability * ln(probability.toDouble())
            }
        }.toFloat()
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
