package io.openeden.relationship

import io.thymos.AffectState
import io.thymos.TextAffectPredictor
import io.thymos.ThymosAffectAnalyzer
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
            delegate = ThymosAffectAnalyzer(
                predictor = object : TextAffectPredictor {
                    override fun predict(text: String): FloatArray {
                        received = text
                        return floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.9f)
                    }
                    override fun close() = Unit
                },
            ),
        )

        analyzer.analyze("别担心，我只是有点累")

        assertEquals("别担心，我只是有点累", received)
    }

    @Test
    fun `reports Thymos inference engine description`() {
        val analyzer = DjlTextAffectAnalyzer(
            delegate = ThymosAffectAnalyzer(
                predictor = object : TextAffectPredictor {
                    override val inferenceEngineDescription: String = "thymos=djl engine=PyTorch device=gpu(0)"
                    override fun predict(text: String): FloatArray =
                        floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.9f)
                    override fun close() = Unit
                },
            ),
        )

        assertEquals("thymos=djl engine=PyTorch device=gpu(0)", analyzer.inferenceEngineDescription)
    }

    @Test
    fun `Thymos fallback is mapped into OpenEden state`() = runTest {
        val analyzer = DjlTextAffectAnalyzer(
            delegate = ThymosAffectAnalyzer(
                predictor = object : TextAffectPredictor {
                    override fun predict(text: String): FloatArray = floatArrayOf(Float.NaN)
                    override fun close() = Unit
                },
                fallback = { AffectState.Uncertain.copy(confidence = 0.25f) },
            ),
        )

        assertEquals(0.25f, analyzer.analyze("今天是星期三").confidence)
    }

    @Test
    fun `trained Qwen affect bundle loads through DJL`() = runTest {
        val bundlePath = Path.of("../data/models/thymos-6d").toAbsolutePath().normalize()
        if (!Files.exists(bundlePath.resolve("model.pt"))) return@runTest
        val analyzer = DjlTextAffectAnalyzer.fromQwenBundle(bundlePath)
        try {
            val result = analyzer.analyze("别担心，我只是有点累")
            assertTrue(listOf(result.valence, result.arousal, result.dominance, result.connectionNeed, result.openness, result.confidence).all(Float::isFinite))
            if (System.getenv("OPENEDEN_THYMOS_DEVICE")?.lowercase() in setOf("gpu", "cuda")) {
                assertEquals("thymos=djl engine=PyTorch device=gpu(0)", analyzer.inferenceEngineDescription)
            }
        } finally {
            analyzer.close()
        }
    }
}
