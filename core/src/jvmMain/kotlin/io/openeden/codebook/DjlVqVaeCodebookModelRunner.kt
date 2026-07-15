package io.openeden.codebook

import io.openeden.bio.BioVector
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path
import kotlin.math.sqrt

class DjlVqVaeCodebookModelRunner(
    private val predictor: DjlFloatPredictor,
    private val inputDimension: Int,
    codebook: List<CodebookVector>,
    private val topK: Int = 3,
) : CodebookModelRunner, AutoCloseable {
    private val predictorMutex = Mutex()
    private val codebook = codebook.toList()

    init {
        require(inputDimension == 8) { "VQ-VAE input must contain the stored 8D vector only" }
        require(this.codebook.isNotEmpty()) { "VQ-VAE codebook must not be empty" }
        val dimension = this.codebook.first().embedding.size
        require(dimension > 0) { "VQ-VAE codebook embedding dimension must be positive" }
        require(this.codebook.all { it.embedding.size == dimension && it.embedding.all(Float::isFinite) }) {
            "VQ-VAE codebook embeddings must have one finite dimension"
        }
    }

    override suspend fun predict(vector: BioVector, dissonance: Float): CodebookModelResult =
        predictorMutex.withLock {
            val input = vector.toList().toFloatArray()
            require(input.size == inputDimension && input.all(Float::isFinite)) {
                "VQ-VAE input must be finite and have dimension $inputDimension"
            }
            val latent = predictor.predict(input)
            require(latent.isNotEmpty() && latent.all(Float::isFinite)) {
                "DJL predictor returned an invalid latent vector"
            }
            val ranked = codebook
                .map { node -> node.nodeId to cosine(latent, node.embedding) }
                .sortedByDescending { it.second }
                .take(topK.coerceAtLeast(1))
            CodebookModelResult(
                nodeIds = ranked.map { it.first },
                confidence = ranked.firstOrNull()?.second?.coerceIn(0.0f, 1.0f) ?: 0.0f,
            )
        }

    override fun close() = predictor.close()

    private fun cosine(left: FloatArray, right: List<Float>): Float {
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

    companion object {
        fun fromModelPath(
            modelPath: Path,
            modelName: String,
            engineName: String,
            inputDimension: Int,
            codebook: List<CodebookVector>,
            topK: Int = 3,
        ): DjlVqVaeCodebookModelRunner {
            return DjlVqVaeCodebookModelRunner(
                predictor = DjlFloatPredictor.fromModelPath(modelPath, modelName, engineName),
                inputDimension = inputDimension,
                codebook = codebook,
                topK = topK,
            )
        }
    }
}
