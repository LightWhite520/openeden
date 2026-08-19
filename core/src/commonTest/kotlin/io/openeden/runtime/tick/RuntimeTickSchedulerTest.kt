package io.openeden.runtime.tick

import io.openeden.runtime.affect.OmegaAccumulationConfig
import io.openeden.runtime.affect.OmegaState
import io.openeden.runtime.affect.ShockState
import io.openeden.runtime.inference.DirectInferenceExecutor
import io.openeden.runtime.inference.InferenceExecutor
import io.openeden.runtime.inference.RecordingInferenceExecutor
import io.openeden.runtime.session.MutableSessionStateStore
import io.openeden.runtime.session.SessionStateStore
import io.openeden.runtime.state.RuntimeConfig
import io.openeden.runtime.state.VectorWriteService


import io.openeden.bio.BioVector
import io.openeden.bio.VectorDelta
import io.openeden.trace.TraceTag
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant

class RuntimeTickSchedulerTest {
    @Test
    fun `tick applies each persisted interval once and persists its anchor`() = runTest {
        val store = MutableSessionStateStore()
        val initial = SessionStateStore.neutral("QQ:drift").copy(lastRuntimeTickAtMs = 0L)
        store.write(initial)
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

        val firstResult = scheduler.evaluateOnce(nowMs = 1_000L)
        val first = store.read("QQ:drift")
        val firstExpected = initial.vector.apply(fluctuation.deltaBetween(0L, 1_000L))

        assertEquals(firstExpected, first.vector)
        assertEquals(1_000L, first.lastRuntimeTickAtMs)

        val result = scheduler.evaluateOnce(nowMs = 2_000L)

        val state = store.read("QQ:drift")
        assertEquals(0, state.evolutionIndex)
        assertEquals(firstExpected.apply(fluctuation.deltaBetween(1_000L, 2_000L)), state.vector)
        assertEquals(2_000L, state.lastRuntimeTickAtMs)
        assertEquals(2, executor.calls)
        assertContains(firstResult.single().traceTags, TraceTag.BackgroundDrift)
        assertContains(result.single().traceTags, TraceTag.BackgroundDrift)
    }

    @Test
    fun `first tick records a baseline without applying historical drift`() = runTest {
        val store = MutableSessionStateStore()
        val initial = SessionStateStore.neutral("QQ:baseline").copy(omega = OmegaState(0.2f))
        store.write(initial)
        val scheduler = RuntimeTickScheduler(
            store = store,
            writer = VectorWriteService(store),
            fluctuation = constantFluctuation(),
            inferenceExecutor = RecordingInferenceExecutor(),
            config = RuntimeConfig.Default.copy(
                omega = OmegaAccumulationConfig(sWearRate = 0.1f, dissonanceWearRate = 0.1f),
            ),
            startedAtMs = 0L,
        )

        val result = scheduler.evaluateOnce(nowMs = 10_000L)

        val state = store.read("QQ:baseline")
        assertEquals(initial.vector, state.vector)
        assertEquals(initial.omega, state.omega)
        assertEquals(10_000L, state.lastRuntimeTickAtMs)
        assertContains(result.single().traceTags, TraceTag.RuntimeTickBaseline)
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
                lastRuntimeTickAtMs = 0L,
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
                lastRuntimeTickAtMs = 0L,
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

    @Test
    fun `tick cannot overwrite a concurrent turn update`() = runTest {
        val store = MutableSessionStateStore()
        val initial = SessionStateStore.neutral("QQ:serialized").copy(lastRuntimeTickAtMs = 0L)
        store.write(initial)
        val writer = VectorWriteService(store)
        val enteredInference = CompletableDeferred<Unit>()
        val releaseInference = CompletableDeferred<Unit>()
        val executor = object : InferenceExecutor {
            override suspend fun <T> run(block: suspend () -> T): T {
                enteredInference.complete(Unit)
                releaseInference.await()
                return block()
            }
        }
        val scheduler = RuntimeTickScheduler(
            store = store,
            writer = writer,
            fluctuation = constantFluctuation(),
            inferenceExecutor = executor,
            config = RuntimeConfig.Default.copy(
                omega = OmegaAccumulationConfig(sWearRate = 0.0f, dissonanceWearRate = 0.0f),
            ),
            startedAtMs = 0L,
        )

        val tickJob = async { scheduler.evaluateOnce(nowMs = 1_000L) }
        enteredInference.await()
        val turnJob = async {
            writer.update("QQ:serialized") { state ->
                state.copy(vector = state.vector.apply(VectorDelta(p = 0.1f)))
            }
        }

        kotlinx.coroutines.yield()
        assertFalse(turnJob.isCompleted)
        releaseInference.complete(Unit)
        tickJob.await()
        turnJob.await()

        val expected = initial.vector
            .apply(constantFluctuation().deltaBetween(0L, 1_000L))
            .apply(VectorDelta(p = 0.1f))
        val state = store.read("QQ:serialized")
        assertEquals(expected, state.vector)
        assertEquals(1_000L, state.lastRuntimeTickAtMs)
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
