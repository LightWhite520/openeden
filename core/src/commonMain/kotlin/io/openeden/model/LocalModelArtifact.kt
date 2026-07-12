package io.openeden.model

import io.openeden.codebook.CodebookDictionary
import io.openeden.codebook.LocalVqVaeCodebookModelRunner
import io.openeden.codebook.LocalVqVaeSpec
import io.openeden.codebook.VqVaeCodebookQuantizer
import io.openeden.memory.CompositeMemoryEmbeddingModel
import io.openeden.memory.LocalNeuralEmotionalEmbeddingModel
import io.openeden.memory.LocalNeuralTextEmbeddingModel
import io.openeden.memory.LocalTextEmbeddingSpec
import io.openeden.memory.MemoryEmbeddingModel
import io.openeden.nn.LocalMlpSpec
import io.openeden.relationship.DeterministicUserAffectAnalyzer
import io.openeden.relationship.LocalTextAffectAnalyzer
import io.openeden.relationship.UserAffectAnalyzer
import kotlinx.serialization.Serializable

@Serializable
data class LocalModelArtifact(
    val schemaVersion: Int = 1,
    val codebookCsv: String,
    val vqVae: LocalVqVaeSpec,
    val textEmbedding: LocalTextEmbeddingSpec,
    val emotionalEmbedding: LocalMlpSpec,
    val textAffect: LocalMlpSpec? = null,
) {
    init {
        require(schemaVersion == 1) { "Unsupported local model artifact schemaVersion=$schemaVersion" }
        require(codebookCsv.isNotBlank()) { "codebookCsv must not be blank" }
    }

    fun codebookQuantizer(minConfidence: Float = 0.6f): VqVaeCodebookQuantizer =
        VqVaeCodebookQuantizer(
            modelRunner = LocalVqVaeCodebookModelRunner(vqVae),
            dictionary = CodebookDictionary.parseCsv(codebookCsv),
            minConfidence = minConfidence,
        )

    fun memoryEmbeddingModel(): MemoryEmbeddingModel =
        CompositeMemoryEmbeddingModel(
            textModel = LocalNeuralTextEmbeddingModel(textEmbedding),
            emotionalModel = LocalNeuralEmotionalEmbeddingModel(emotionalEmbedding),
        )

    fun userAffectAnalyzer(): UserAffectAnalyzer = textAffect?.let {
        LocalTextAffectAnalyzer(textEmbedding, it)
    } ?: DeterministicUserAffectAnalyzer()
}
