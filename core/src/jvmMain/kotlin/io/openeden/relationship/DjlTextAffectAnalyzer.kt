package io.openeden.relationship

import io.openeden.codebook.DjlFloatPredictor
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path
import kotlin.math.sqrt

class DjlTextAffectAnalyzer(
    private val predictor: DjlFloatPredictor,
    private val textInputDimension: Int,
    private val fallback: UserAffectAnalyzer = DeterministicUserAffectAnalyzer(),
) : UserAffectAnalyzer, AutoCloseable {
    private val mutex = Mutex()

    init {
        require(textInputDimension > 0)
    }

    override suspend fun analyze(text: String): UserAffectState = mutex.withLock {
        runCatching {
            val buckets = FloatArray(textInputDimension)
            for ((index, char) in text.withIndex()) {
                buckets[(char.code * 31 + index).mod(textInputDimension)] += 1.0f
            }
            var norm = 0.0f
            for (value in buckets) norm += value * value
            val input = if (norm == 0.0f) buckets else buckets.map { it / sqrt(norm) }.toFloatArray()
            val output = predictor.predict(input)
            require(output.size == 6 && output.all(Float::isFinite)) { "DJL affect output must contain six finite values" }
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

    override fun close() = predictor.close()

    companion object {
        fun fromModelPath(modelPath: Path, modelName: String, engineName: String, textInputDimension: Int): DjlTextAffectAnalyzer =
            DjlTextAffectAnalyzer(
                predictor = DjlFloatPredictor.fromModelPath(modelPath, modelName, engineName),
                textInputDimension = textInputDimension,
            )
    }
}
