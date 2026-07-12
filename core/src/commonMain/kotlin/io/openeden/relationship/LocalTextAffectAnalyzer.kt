package io.openeden.relationship

import io.openeden.memory.LocalNeuralTextEmbeddingModel
import io.openeden.memory.LocalTextEmbeddingSpec
import io.openeden.nn.LocalMlp
import io.openeden.nn.LocalMlpSpec

class LocalTextAffectAnalyzer(
    textEmbeddingSpec: LocalTextEmbeddingSpec,
    affectSpec: LocalMlpSpec,
    private val fallback: UserAffectAnalyzer = DeterministicUserAffectAnalyzer(),
) : UserAffectAnalyzer {
    private val textModel = LocalNeuralTextEmbeddingModel(textEmbeddingSpec)
    private val classifier = LocalMlp(affectSpec)

    init {
        require(affectSpec.layers.last().outputSize == 6) { "Text affect classifier must output 6 values" }
    }

    override suspend fun analyze(text: String): UserAffectState = runCatching {
        val output = classifier.forward(textModel.embed(text))
        require(output.size == 6 && output.all(Float::isFinite)) { "Text affect model returned invalid output" }
        UserAffectState(
            valence = output[0].coerceIn(0.0f, 1.0f),
            arousal = output[1].coerceIn(0.0f, 1.0f),
            dominance = output[2].coerceIn(0.0f, 1.0f),
            connectionNeed = output[3].coerceIn(0.0f, 1.0f),
            openness = output[4].coerceIn(0.0f, 1.0f),
            confidence = output[5].coerceIn(0.0f, 1.0f),
        )
    }.getOrElse { fallback.analyze(text) }
}
