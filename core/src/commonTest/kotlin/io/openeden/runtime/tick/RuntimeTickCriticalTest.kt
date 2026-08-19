package io.openeden.runtime.tick

import io.openeden.bio.BioVector
import io.openeden.runtime.affect.OmegaAccumulationConfig
import io.openeden.runtime.affect.OmegaState
import io.openeden.runtime.inference.DirectInferenceExecutor
import io.openeden.runtime.session.MutableSessionStateStore
import io.openeden.runtime.state.RuntimeConfig
import io.openeden.runtime.state.VectorWriteService
import io.openeden.trace.TraceTag
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeTickCriticalTest {
    @Test
    fun `omega threshold reports critical state after the serialized tick write`() = runTest {
        val store = MutableSessionStateStore()
        store.write(
            io.openeden.runtime.session.SessionStateStore.neutral("CLI:critical").copy(
                vector = BioVector.Neutral.copy(s = 0.9f, f = 0.9f),
                omega = OmegaState(0.2f),
                lastRuntimeTickAtMs = 0L,
            ),
        )
        val criticalSessions = mutableListOf<String>()
        val scheduler = RuntimeTickScheduler(
            store = store,
            writer = VectorWriteService(store),
            fluctuation = SineWaveFluctuationEngine(
                SineWaveFluctuationProfile(List(8) { SineWaveDimension(0.0f, 0.002f, 0.1f) }),
            ),
            inferenceExecutor = DirectInferenceExecutor,
            config = RuntimeConfig.Default.copy(
                omega = OmegaAccumulationConfig(
                    sWearRate = 0.01f,
                    dissonanceWearRate = 0.0f,
                    fearEntropyMultiplier = 2.0f,
                ),
                omegaCriticalThreshold = 0.21f,
            ),
            startedAtMs = 0L,
            onOmegaCritical = { sessionId -> criticalSessions += sessionId },
        )

        val results = scheduler.evaluateOnce(nowMs = 1_000L)

        assertEquals(listOf("CLI:critical"), criticalSessions)
        assertEquals(true, results.single().critical)
        kotlin.test.assertContains(results.single().traceTags, TraceTag.OmegaCritical)
        assertEquals(0.22f, store.read("CLI:critical").omega.value, 0.0001f)
    }
}
