package io.openeden.relationship

import io.openeden.codebook.DjlFloatPredictor
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DjlTextAffectAnalyzerTest {
    @Test
    fun `text predictor receives original text`() = runTest {
        var received = ""
        val analyzer = DjlTextAffectAnalyzer(
            predictor = object : TextAffectPredictor {
                override fun predict(text: String): FloatArray {
                    received = text
                    return floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.9f)
                }
                override fun close() = Unit
            },
        )

        analyzer.analyze("别担心，我只是有点累")

        assertEquals("别担心，我只是有点累", received)
    }

    @Test
    fun `djl predictor output is mapped to bounded affect state`() = runTest {
        val analyzer = DjlTextAffectAnalyzer(
            predictor = object : DjlFloatPredictor {
                override fun predict(input: FloatArray): FloatArray = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.9f)
                override fun close() = Unit
            },
            textInputDimension = 8,
        )

        val result = analyzer.analyze("hello")

        assertEquals(0.1f, result.valence)
        assertEquals(0.9f, result.confidence)
    }

    @Test
    fun `invalid djl output degrades to deterministic fallback`() = runTest {
        val analyzer = DjlTextAffectAnalyzer(
            predictor = object : DjlFloatPredictor {
                override fun predict(input: FloatArray): FloatArray = floatArrayOf(Float.NaN)
                override fun close() = Unit
            },
            textInputDimension = 8,
        )

        assertTrue(analyzer.analyze("今天是星期三").confidence < 0.5f)
    }

    @Test
    fun `trained affect TorchScript loads through DJL`() = runTest {
        val modelPath = Path.of("../data/models/djl/affect").toAbsolutePath().normalize()
        assertTrue(Files.exists(modelPath.resolve("model.pt")))
        val analyzer = DjlTextAffectAnalyzer.fromModelPath(
            modelPath = modelPath,
            modelName = "model",
            engineName = "PyTorch",
            textInputDimension = 32,
        )
        try {
            val result = analyzer.analyze("我今天有点焦虑，但还是想把事情做好")
            assertTrue(listOf(result.valence, result.arousal, result.dominance, result.connectionNeed, result.openness, result.confidence).all(Float::isFinite))
        } finally {
            analyzer.close()
        }
    }

    @Test
    fun `trained Qwen affect bundle loads through DJL`() = runTest {
        val bundlePath = Path.of("../data/models/user-affect-qwen").toAbsolutePath().normalize()
        if (!Files.exists(bundlePath.resolve("model.pt"))) return@runTest
        val analyzer = DjlTextAffectAnalyzer.fromQwenBundle(bundlePath)
        try {
            val result = analyzer.analyze("别担心，我只是有点累")
            assertTrue(listOf(result.valence, result.arousal, result.dominance, result.connectionNeed, result.openness, result.confidence).all(Float::isFinite))
        } finally {
            analyzer.close()
        }
    }
}
