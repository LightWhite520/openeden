package io.openeden.codebook

import io.openeden.bio.BioVector
import io.openeden.trace.TraceTag

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
