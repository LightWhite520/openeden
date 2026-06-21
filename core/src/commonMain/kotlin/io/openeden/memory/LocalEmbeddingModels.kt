package io.openeden.memory

import io.openeden.bio.BioVector
import io.openeden.nn.LocalMlp
import io.openeden.nn.LocalMlpSpec
import io.openeden.nn.l2Normalize
import kotlinx.serialization.Serializable

interface TextEmbeddingModel {
    suspend fun embed(text: String): List<Float>
}

interface EmotionalEmbeddingModel {
    suspend fun embed(vector: BioVector): List<Float>
}

interface MemoryEmbeddingModel : TextEmbeddingModel, EmotionalEmbeddingModel

@Serializable
data class LocalTextEmbeddingSpec(
    val bucketSize: Int,
    val projector: LocalMlpSpec,
)

class LocalNeuralTextEmbeddingModel(
    spec: LocalTextEmbeddingSpec,
) : TextEmbeddingModel {
    private val bucketSize = spec.bucketSize
    private val projector = LocalMlp(spec.projector)

    override suspend fun embed(text: String): List<Float> {
        val buckets = FloatArray(bucketSize)
        for ((index, char) in text.withIndex()) {
            val bucket = (char.code * 31 + index).mod(bucketSize)
            buckets[bucket] += 1.0f
        }
        return l2Normalize(projector.forward(l2Normalize(buckets.toList())))
    }
}

class LocalNeuralEmotionalEmbeddingModel(
    spec: LocalMlpSpec,
) : EmotionalEmbeddingModel {
    private val projector = LocalMlp(spec)

    override suspend fun embed(vector: BioVector): List<Float> =
        l2Normalize(projector.forward(vector.toList()))
}

class CompositeMemoryEmbeddingModel(
    private val textModel: TextEmbeddingModel,
    private val emotionalModel: EmotionalEmbeddingModel,
) : MemoryEmbeddingModel {
    override suspend fun embed(text: String): List<Float> = textModel.embed(text)
    override suspend fun embed(vector: BioVector): List<Float> = emotionalModel.embed(vector)
}

object DeterministicMemoryEmbeddingModel : MemoryEmbeddingModel {
    override suspend fun embed(text: String): List<Float> = InMemoryMemoryPalace.embedText(text)
    override suspend fun embed(vector: BioVector): List<Float> = InMemoryMemoryPalace.embedVector(vector)
}
