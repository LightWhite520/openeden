package io.openeden.codebook

import io.openeden.bio.BioVector
import io.openeden.trace.TraceTag
import kotlinx.serialization.Serializable
import kotlin.math.sqrt

interface CodebookQuantizer {
    suspend fun quantize(vector: BioVector, dissonance: Float): QuantizationResult
}

interface CodebookModelRunner {
    suspend fun predict(vector: BioVector, dissonance: Float): CodebookModelResult
}

data class CodebookModelResult(
    val nodeIds: List<String>,
    val confidence: Float,
)

data class QuantizationResult(
    val activeNodes: List<String>,
    val semanticDefinitions: List<String>,
    val confidence: Float,
    val traceTags: Set<String> = emptySet(),
)

class HeuristicCodebookFallback : CodebookQuantizer {
    override suspend fun quantize(vector: BioVector, dissonance: Float): QuantizationResult {
        val definitions = listOf(
            "Logical clarity: ${level(vector.l)}",
            "Emotional intensity: ${level(vector.p)}",
            "Self-model: ${selfModel(vector.e)}",
            "System stability: ${stability(vector.s)}",
            "Memory pull: ${memoryPull(vector.tau)}",
            "Vitality: ${vitality(vector.v)}",
            "Empathy mirror: ${if (vector.m > HIGH) "ACTIVE" else "PASSIVE"}",
            "Fear level: ${level(vector.f)}",
            "Dissonance (derived): ${level(dissonance)}",
        )
        return QuantizationResult(
            activeNodes = listOf("HEURISTIC_FALLBACK"),
            semanticDefinitions = definitions,
            confidence = 1.0f,
            traceTags = setOf(
                TraceTag.CodebookHeuristicFallback,
                "codebook_fallback_reason=explicit_heuristic",
            ),
        )
    }

    private fun level(value: Float): String = when {
        value > HIGH -> "HIGH"
        value < LOW -> "LOW"
        else -> "MED"
    }

    private fun selfModel(value: Float): String = when {
        value > HIGH -> "FEELING"
        value < LOW -> "MECHANICAL"
        else -> "NEUTRAL"
    }

    private fun stability(value: Float): String = when {
        value > HIGH -> "CHAOTIC"
        value < LOW -> "STABLE"
        else -> "UNSTABLE"
    }

    private fun memoryPull(value: Float): String = when {
        value > HIGH -> "STRONG"
        value < LOW -> "WEAK"
        else -> "NORMAL"
    }

    private fun vitality(value: Float): String = when {
        value > HIGH -> "HIGH"
        value < LOW -> "EXHAUSTED"
        else -> "MED"
    }

    private companion object {
        const val HIGH = 0.6f
        const val LOW = 0.3f
    }
}

class VqVaeCodebookQuantizer(
    private val modelRunner: CodebookModelRunner,
    private val dictionary: CodebookDictionary,
    private val fallback: CodebookQuantizer = HeuristicCodebookFallback(),
    private val minConfidence: Float = 0.6f,
) : CodebookQuantizer {
    override suspend fun quantize(vector: BioVector, dissonance: Float): QuantizationResult {
        val modelResult = runCatching { modelRunner.predict(vector, dissonance) }.getOrNull()
            ?: return degradedFallback(vector, dissonance, "predictor_failure")
        if (!modelResult.confidence.isFinite()) {
            return degradedFallback(vector, dissonance, "non_finite_confidence")
        }
        if (modelResult.confidence < minConfidence || modelResult.nodeIds.isEmpty()) {
            return degradedFallback(vector, dissonance, "low_confidence_or_empty")
        }
        val definitions = dictionary.definitionsFor(modelResult.nodeIds)
        if (definitions.isEmpty()) {
            return degradedFallback(vector, dissonance, "missing_dictionary_definition")
        }
        return QuantizationResult(
            activeNodes = modelResult.nodeIds,
            semanticDefinitions = definitions,
            confidence = modelResult.confidence.coerceIn(0.0f, 1.0f),
            traceTags = setOf(TraceTag.CodebookQuantized),
        )
    }

    private suspend fun degradedFallback(
        vector: BioVector,
        dissonance: Float,
        reason: String,
    ): QuantizationResult {
        val result = fallback.quantize(vector, dissonance)
        return result.copy(traceTags = result.traceTags + "codebook_fallback_reason=$reason")
    }
}

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

@Serializable
data class CodebookVector(
    val nodeId: String,
    val embedding: List<Float>,
)
