package io.openeden.codebook

import io.openeden.bio.BioVector
import io.openeden.nn.LocalMlp
import io.openeden.nn.LocalMlpSpec
import io.openeden.nn.normalizedCosine
import kotlinx.serialization.Serializable

@Serializable
data class LocalVqVaeSpec(
    val encoder: LocalMlpSpec,
    val codebook: List<CodebookVector>,
    val topK: Int = 3,
)

class LocalVqVaeCodebookModelRunner(
    spec: LocalVqVaeSpec,
) : CodebookModelRunner {
    private val encoder = LocalMlp(spec.encoder)
    private val codebook = spec.codebook
    private val topK = spec.topK.coerceAtLeast(1)

    override suspend fun predict(vector: BioVector, dissonance: Float): CodebookModelResult {
        if (codebook.isEmpty()) return CodebookModelResult(emptyList(), 0.0f)
        val encoded = encoder.forward(vector.toList() + dissonance.coerceIn(0.0f, 1.0f))
        val ranked = codebook
            .map { node -> node.nodeId to normalizedCosine(encoded, node.embedding) }
            .sortedByDescending { it.second }
            .take(topK)
        return CodebookModelResult(
            nodeIds = ranked.map { it.first },
            confidence = ranked.firstOrNull()?.second?.coerceIn(0.0f, 1.0f) ?: 0.0f,
        )
    }
}
