package io.openeden.runtime.state

import io.openeden.bio.BioVector
import io.openeden.runtime.affect.OmegaAccumulationConfig
import io.openeden.runtime.affect.OmegaState
import io.openeden.runtime.affect.ShockState
import io.openeden.runtime.tick.SineWaveDimension
import io.openeden.runtime.tick.SineWaveFluctuationEngine
import io.openeden.runtime.tick.SineWaveFluctuationProfile
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class BackgroundDynamicsReducerTest {
    @Test
    fun `split fluctuation intervals equal uninterrupted composition`() {
        val fluctuation = SineWaveFluctuationEngine(
            SineWaveFluctuationProfile(
                dimensions = List(8) {
                    SineWaveDimension(amplitude = 0.01f, frequencyHz = 0.002f, phaseRadians = 0.1f)
                },
            ),
        )
        val reducer = BackgroundDynamicsReducer(
            fluctuation = fluctuation,
            omegaConfig = OmegaAccumulationConfig(sWearRate = 0.0f, dissonanceWearRate = 0.0f),
            startedAtMs = 0L,
        )

        val first = reducer.reduce(BioVector.Neutral, null, OmegaState(0.0f), 0L, 1_000L)
        val second = reducer.reduce(first.vector, first.shockState, first.omega, 1_000L, 2_000L)
        val uninterrupted = BioVector.Neutral.apply(fluctuation.deltaBetween(0L, 2_000L))

        assertEquals(uninterrupted, second.vector)
        assertEquals(2_000L, second.consumedAtMs)
        assertEquals(1_000L, second.elapsedMillis)
        assertFalse(second.baseline)
    }

    @Test
    fun `split shock decay and omega wear equal uninterrupted interval`() {
        val reducer = BackgroundDynamicsReducer(
            fluctuation = zeroFluctuation(),
            omegaConfig = OmegaAccumulationConfig(
                highThreshold = 0.75f,
                sWearRate = 0.01f,
                dissonanceWearRate = 0.0f,
                fearEntropyMultiplier = 2.0f,
            ),
            startedAtMs = 0L,
        )
        val vector = BioVector.Neutral.copy(s = 0.9f, f = 0.9f)
        val shock = ShockState(
            active = true,
            intensity = 0.8f,
            description = "shock",
            triggeredAt = Instant.fromEpochMilliseconds(0L),
            decayLambda = 0.1f,
        )

        val first = reducer.reduce(vector, shock, OmegaState(0.2f), 0L, 1_000L)
        val second = reducer.reduce(first.vector, first.shockState, first.omega, 1_000L, 2_000L)
        val uninterrupted = reducer.reduce(vector, shock, OmegaState(0.2f), 0L, 2_000L)

        assertEquals(0.8f * exp(-0.2f), second.shockState!!.intensity, 0.000001f)
        assertEquals(uninterrupted.shockState!!.intensity, second.shockState!!.intensity, 0.000001f)
        assertEquals(0.24f, second.omega.value, 0.000001f)
        assertEquals(uninterrupted.omega.value, second.omega.value, 0.000001f)
    }

    @Test
    fun `missing anchor records baseline without historical mechanics`() {
        val reducer = BackgroundDynamicsReducer(
            fluctuation = zeroFluctuation(),
            omegaConfig = OmegaAccumulationConfig(sWearRate = 1.0f, dissonanceWearRate = 1.0f),
            startedAtMs = 0L,
        )
        val vector = BioVector.Neutral.copy(s = 0.9f, f = 0.9f)
        val shock = ShockState(
            active = true,
            intensity = 0.8f,
            description = "shock",
            triggeredAt = Instant.fromEpochMilliseconds(0L),
            decayLambda = 1.0f,
        )

        val result = reducer.reduce(vector, shock, OmegaState(0.2f), null, 10_000L)

        assertTrue(result.baseline)
        assertEquals(vector, result.vector)
        assertEquals(shock, result.shockState)
        assertEquals(0.2f, result.omega.value)
        assertEquals(10_000L, result.consumedAtMs)
        assertEquals(0L, result.elapsedMillis)
    }

    private fun zeroFluctuation(): SineWaveFluctuationEngine = SineWaveFluctuationEngine(
        SineWaveFluctuationProfile(
            dimensions = List(8) {
                SineWaveDimension(amplitude = 0.0f, frequencyHz = 0.002f, phaseRadians = 0.1f)
            },
        ),
    )
}
