package io.openeden.runtime

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SineWaveFluctuationEngineTest {
    @Test
    fun `same parameters produce deterministic bounded fluctuation`() {
        val engine = SineWaveFluctuationEngine(
            SineWaveFluctuationProfile(
                dimensions = List(8) { index ->
                    SineWaveDimension(
                        amplitude = 0.02f + index * 0.001f,
                        frequencyHz = 0.001f + index * 0.0001f,
                        phaseRadians = index * 0.3f,
                        secondaryAmplitudeRatio = 0.35f,
                        secondaryFrequencyMultiplier = 2.7f,
                    )
                },
            ),
        )

        val first = engine.deltaAt(elapsedMillis = 60_000L)
        val second = engine.deltaAt(elapsedMillis = 60_000L)

        assertEquals(first, second)
        first.toList().forEach { value ->
            assertTrue(abs(value) <= 0.25f)
        }
    }

    @Test
    fun `fluctuation changes smoothly over time`() {
        val engine = SineWaveFluctuationEngine(
            SineWaveFluctuationProfile(
                dimensions = List(8) {
                    SineWaveDimension(
                        amplitude = 0.04f,
                        frequencyHz = 0.002f,
                        phaseRadians = 0.1f,
                    )
                },
            ),
        )

        val early = engine.deltaAt(elapsedMillis = 1_000L)
        val later = engine.deltaAt(elapsedMillis = 2_000L)

        assertTrue(early != later)
        early.toList().zip(later.toList()).forEach { (a, b) ->
            assertTrue(abs(a - b) < 0.05f)
        }
    }
}
