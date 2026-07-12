package io.openeden.codebook

import ai.djl.Model
import ai.djl.ndarray.NDList
import ai.djl.translate.Translator
import ai.djl.translate.TranslatorContext
import ai.djl.inference.Predictor
import io.openeden.bio.BioVector
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path
import kotlin.math.sqrt

interface DjlFloatPredictor : AutoCloseable {
    fun predict(input: FloatArray): FloatArray

    companion object {
        fun fromModelPath(
            modelPath: Path,
            modelName: String,
            engineName: String,
        ): DjlFloatPredictor {
            val model = Model.newInstance(modelName, engineName)
            model.load(modelPath, modelName)
            return DjlModelFloatPredictor(model.newPredictor(FloatArrayTranslator()), model)
        }
    }
}

class DjlVqVaeCodebookModelRunner(
    private val predictor: DjlFloatPredictor,
    private val inputDimension: Int,
    codebook: List<CodebookVector>,
    private val topK: Int = 3,
) : CodebookModelRunner, AutoCloseable {
    private val predictorMutex = Mutex()
    private val codebook = codebook.toList()

    init {
        require(inputDimension == 9) { "VQ-VAE input must contain 8D vector plus derived D" }
        require(this.codebook.isNotEmpty()) { "VQ-VAE codebook must not be empty" }
        val dimension = this.codebook.first().embedding.size
        require(dimension > 0) { "VQ-VAE codebook embedding dimension must be positive" }
        require(this.codebook.all { it.embedding.size == dimension && it.embedding.all(Float::isFinite) }) {
            "VQ-VAE codebook embeddings must have one finite dimension"
        }
    }

    override suspend fun predict(vector: BioVector, dissonance: Float): CodebookModelResult =
        predictorMutex.withLock {
            val input = (vector.toList() + dissonance).toFloatArray()
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

private class DjlModelFloatPredictor(
    private val predictor: Predictor<FloatArray, FloatArray>,
    private val model: Model,
) : DjlFloatPredictor {
    override fun predict(input: FloatArray): FloatArray = predictor.predict(input)
    override fun close() {
        predictor.close()
        model.close()
    }
}

private class FloatArrayTranslator : Translator<FloatArray, FloatArray> {
    override fun processInput(context: TranslatorContext, input: FloatArray): NDList =
        NDList(context.ndManager.create(input))

    override fun processOutput(context: TranslatorContext, list: NDList): FloatArray =
        list.singletonOrThrow().toFloatArray()
}
