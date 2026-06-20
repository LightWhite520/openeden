package io.openeden.runtime

import io.openeden.bio.BioVector
import io.openeden.trace.TraceTag
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant

class RuntimeTickSchedulerTest {
    @Test
    fun `tick applies sine drift and does not increment evolution index`() = runTest {
        val store = MutableSessionStateStore()
        store.write(SessionStateStore.neutral("QQ:drift"))
        val writer = VectorWriteService(store)
        val fluctuation = constantFluctuation()
        val executor = RecordingInferenceExecutor()
        val scheduler = RuntimeTickScheduler(
            store = store,
            writer = writer,
            fluctuation = fluctuation,
            inferenceExecutor = executor,
            config = RuntimeConfig.Default.copy(omega = OmegaAccumulationConfig(sWearRate = 0.0f, dissonanceWearRate = 0.0f)),
            startedAtMs = 0L,
        )

        val result = scheduler.evaluateOnce(nowMs = 1_000L)

        val state = store.read("QQ:drift")
        assertEquals(0, state.evolutionIndex)
        assertEquals(0.5f + fluctuation.deltaAt(1_000L).p, state.vector.p, 0.0001f)
        assertEquals(1, executor.calls)
        assertContains(result.single().traceTags, TraceTag.BackgroundDrift)
    }

    @Test
    fun `tick decays shock state and marks inactive below threshold`() = runTest {
        val store = MutableSessionStateStore()
        store.write(
            SessionStateStore.neutral("QQ:shock").copy(
                shockState = ShockState(
                    active = true,
                    intensity = 0.06f,
                    description = "shock",
                    triggeredAt = Instant.fromEpochMilliseconds(0),
                    decayLambda = 1.0f,
                    shockHeartbeatFired = true,
                ),
            ),
        )
        val scheduler = RuntimeTickScheduler(
            store = store,
            writer = VectorWriteService(store),
            fluctuation = constantFluctuation(),
            inferenceExecutor = DirectInferenceExecutor,
            config = RuntimeConfig.Default.copy(omega = OmegaAccumulationConfig(sWearRate = 0.0f, dissonanceWearRate = 0.0f)),
            startedAtMs = 0L,
        )

        val result = scheduler.evaluateOnce(nowMs = 10_000L)

        val shock = store.read("QQ:shock").shockState!!
        assertFalse(shock.active)
        assertEquals(true, shock.shockHeartbeatFired)
        assertContains(result.single().traceTags, TraceTag.ShockStateDecayed)
    }

    @Test
    fun `tick accumulates omega without reducing it`() = runTest {
        val store = MutableSessionStateStore()
        store.write(
            SessionStateStore.neutral("QQ:omega").copy(
                vector = BioVector.Neutral.copy(s = 0.9f, f = 0.9f),
                omega = OmegaState(0.2f),
            ),
        )
        val scheduler = RuntimeTickScheduler(
            store = store,
            writer = VectorWriteService(store),
            fluctuation = zeroFluctuation(),
            inferenceExecutor = DirectInferenceExecutor,
            config = RuntimeConfig.Default.copy(
                omega = OmegaAccumulationConfig(
                    highThreshold = 0.75f,
                    sWearRate = 0.01f,
                    dissonanceWearRate = 0.0f,
                    fearEntropyMultiplier = 2.0f,
                ),
            ),
            startedAtMs = 0L,
        )

        val result = scheduler.evaluateOnce(nowMs = 1_000L)

        assertEquals(0.22f, store.read("QQ:omega").omega.value, 0.0001f)
        assertContains(result.single().traceTags, TraceTag.OmegaAccumulated)
    }

    private fun constantFluctuation(): SineWaveFluctuationEngine =
        SineWaveFluctuationEngine(
            SineWaveFluctuationProfile(
                dimensions = List(8) {
                    SineWaveDimension(amplitude = 0.04f, frequencyHz = 0.002f, phaseRadians = 0.1f)
                },
            ),
        )

    private fun zeroFluctuation(): SineWaveFluctuationEngine =
        SineWaveFluctuationEngine(
            SineWaveFluctuationProfile(
                dimensions = List(8) {
                    SineWaveDimension(amplitude = 0.0f, frequencyHz = 0.002f, phaseRadians = 0.1f)
                },
            ),
        )
}
