package io.openeden.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class BackgroundDriftSchedulerTest {
    @Test
    fun `background drift applies sine fluctuation without incrementing evolution index`() = runTest {
        val store = MutableSessionStateStore()
        store.write(SessionStateStore.neutral("QQ:drift"))
        val writer = VectorWriteService(store)
        val fluctuation = SineWaveFluctuationEngine(
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
        val scheduler = BackgroundDriftScheduler(
            store = store,
            writer = writer,
            fluctuation = fluctuation,
            startedAtMs = 0L,
        )

        scheduler.evaluateOnce(nowMs = 1_000L)

        val state = store.read("QQ:drift")
        assertEquals(0, state.evolutionIndex)
        assertEquals(0.5f + fluctuation.deltaAt(1_000L).p, state.vector.p, 0.0001f)
    }
}
