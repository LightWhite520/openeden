package io.openeden.codebook

import io.openeden.bio.BioVector
import kotlin.math.sqrt

class NearestVectorCodebookModelRunner(
    private val nodes: List<CodebookVector>,
    private val topK: Int = 3,
) : CodebookModelRunner {
    override suspend fun predict(vector: BioVector, dissonance: Float): CodebookModelResult {
        if (nodes.isEmpty()) return CodebookModelResult(emptyList(), 0.0f)
        val input = vector.toList() + dissonance.coerceIn(0.0f, 1.0f)
        val ranked = nodes
            .map { it.nodeId to cosine(input, it.embedding) }
            .sortedByDescending { it.second }
            .take(topK.coerceAtLeast(1))
        return CodebookModelResult(
            nodeIds = ranked.map { it.first },
            confidence = ranked.firstOrNull()?.second?.coerceIn(0.0f, 1.0f) ?: 0.0f,
        )
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
