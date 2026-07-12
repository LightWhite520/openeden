package io.openeden.memory

import io.openeden.bio.BioVector
import io.openeden.codebook.DjlFloatPredictor
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.sqrt
import java.nio.file.Path

class DjlMemoryEmbeddingModel(
    private val textPredictor: DjlFloatPredictor,
    private val emotionalPredictor: DjlFloatPredictor,
    private val textInputDimension: Int,
    private val emotionalInputDimension: Int = 8,
    private val textBucketSize: Int = textInputDimension,
) : MemoryEmbeddingModel, AutoCloseable {
    private val textMutex = Mutex()
    private val emotionalMutex = Mutex()

    init {
        require(textInputDimension > 0)
        require(emotionalInputDimension == 8)
        require(textBucketSize == textInputDimension)
    }

    override suspend fun embed(text: String): List<Float> = textMutex.withLock {
        val buckets = FloatArray(textBucketSize)
        for ((index, char) in text.withIndex()) {
            buckets[(char.code * 31 + index).mod(textBucketSize)] += 1.0f
        }
        normalize(textPredictor.predict(normalize(buckets).toFloatArray()))
    }

    override suspend fun embed(vector: BioVector): List<Float> = emotionalMutex.withLock {
        val input = vector.toList().toFloatArray()
        require(input.size == emotionalInputDimension && input.all(Float::isFinite))
        normalize(emotionalPredictor.predict(input))
    }

    override fun close() {
        textPredictor.close()
        emotionalPredictor.close()
    }

    companion object {
        fun fromModelPaths(
            textModelPath: Path,
            emotionalModelPath: Path,
            textModelName: String,
            emotionalModelName: String,
            engineName: String,
            textInputDimension: Int,
        ): DjlMemoryEmbeddingModel = DjlMemoryEmbeddingModel(
            textPredictor = io.openeden.codebook.DjlFloatPredictor.fromModelPath(
                textModelPath, textModelName, engineName,
            ),
            emotionalPredictor = io.openeden.codebook.DjlFloatPredictor.fromModelPath(
                emotionalModelPath, emotionalModelName, engineName,
            ),
            textInputDimension = textInputDimension,
        )
    }

    private fun normalize(values: FloatArray): List<Float> {
        require(values.isNotEmpty() && values.all(Float::isFinite)) {
            "DJL embedding output must be finite and non-empty"
        }
        var norm = 0.0f
        for (value in values) norm += value * value
        require(norm > 0.0f) { "DJL embedding output must not be zero" }
        val denominator = sqrt(norm)
        return values.map { it / denominator }
    }
}
