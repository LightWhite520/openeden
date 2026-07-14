package io.openeden.relationship

import io.openeden.codebook.DjlFloatPredictor
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path

class DjlTextAffectAnalyzer(
    private val predictor: TextAffectPredictor,
    private val fallback: UserAffectAnalyzer = DeterministicUserAffectAnalyzer(),
) : UserAffectAnalyzer, AutoCloseable {
    constructor(
        predictor: DjlFloatPredictor,
        textInputDimension: Int,
        fallback: UserAffectAnalyzer = DeterministicUserAffectAnalyzer(),
    ) : this(LegacyHashAffectPredictor(predictor, textInputDimension), fallback)

    private val mutex = Mutex()

    override suspend fun analyze(text: String): UserAffectState = mutex.withLock {
        runCatching {
            val output = predictor.predict(text)
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

        fun fromQwenBundle(bundlePath: Path, engineName: String = "PyTorch"): DjlTextAffectAnalyzer =
            DjlTextAffectAnalyzer(DjlQwenTextAffectPredictor.fromBundle(bundlePath, engineName))
    }
}

private class LegacyHashAffectPredictor(
    private val predictor: DjlFloatPredictor,
    private val inputDimension: Int,
) : TextAffectPredictor {
    init {
        require(inputDimension > 0)
    }

    override fun predict(text: String): FloatArray {
        val buckets = FloatArray(inputDimension)
        for ((index, char) in text.withIndex()) {
            buckets[(char.code * 31 + index).mod(inputDimension)] += 1.0f
        }
        var norm = 0.0f
        for (value in buckets) norm += value * value
        val input = if (norm == 0.0f) buckets else buckets.map { it / kotlin.math.sqrt(norm) }.toFloatArray()
        return predictor.predict(input)
    }

    override fun close() = predictor.close()
}
