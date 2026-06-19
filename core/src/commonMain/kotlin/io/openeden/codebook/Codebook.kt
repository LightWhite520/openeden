package io.openeden.codebook

import io.openeden.bio.BioVector
import io.openeden.trace.TraceTag

interface CodebookQuantizer {
    suspend fun quantize(vector: BioVector, dissonance: Float): QuantizationResult
}

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
            traceTags = setOf(TraceTag.CodebookHeuristicFallback),
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
