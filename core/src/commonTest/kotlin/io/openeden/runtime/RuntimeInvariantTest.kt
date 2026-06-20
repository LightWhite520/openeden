package io.openeden.runtime

import io.openeden.bio.BioVector
import io.openeden.bio.VectorDelta
import io.openeden.trace.TraceTag
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class RuntimeInvariantTest {
    @Test
    fun `pre tick skips low confidence signals`() {
        val original = BioVector.Neutral

        val result = PreTickEngine.apply(
            original = original,
            signal = EmotionSignal(delta = VectorDelta(p = -1.0f), confidence = 0.49f),
        )

        assertTrue(result.skipped)
        assertEquals(original, result.preTicked)
        assertEquals(VectorDelta.Zero, result.appliedDelta)
    }

    @Test
    fun `pre tick scales by confidence and clamps per dimension`() {
        val result = PreTickEngine.apply(
            original = BioVector.Neutral,
            signal = EmotionSignal(delta = VectorDelta(p = -1.0f, f = 1.0f), confidence = 0.8f),
        )

        assertFalse(result.skipped)
        assertEquals(-MAX_PRETICK_DELTA, result.appliedDelta.p)
        assertEquals(MAX_PRETICK_DELTA, result.appliedDelta.f)
        assertEquals(0.25f, result.preTicked.p)
        assertEquals(0.75f, result.preTicked.f)
    }

    @Test
    fun `shock back detection enforces confidence gate`() {
        val lowConfidence = ShockStateEngine.detectFromLlmOutput(
            vectorDelta = VectorDelta(p = -0.8f, f = 0.8f),
            emotionConfidence = 0.64f,
            internalLogic = "logic",
            now = Instant.fromEpochMilliseconds(0),
        )

        val highConfidence = ShockStateEngine.detectFromLlmOutput(
            vectorDelta = VectorDelta(p = -0.8f, f = 0.8f),
            emotionConfidence = 0.65f,
            internalLogic = "logic",
            now = Instant.fromEpochMilliseconds(0),
        )

        assertNull(lowConfidence)
        assertEquals(0.4f, highConfidence?.intensity)
    }

    @Test
    fun `shock update uses EMA and omega jump is monotonic`() {
        val first = ShockStateEngine.update(
            current = null,
            signal = 1.0f,
            description = "first",
            decayLambda = 0.001f,
            now = Instant.fromEpochMilliseconds(0),
        )
        val second = ShockStateEngine.update(
            current = first,
            signal = 1.0f,
            description = "second",
            decayLambda = 0.001f,
            now = Instant.fromEpochMilliseconds(1000),
        )
        val omega = ShockStateEngine.omegaJump(OmegaState(0.5f), second)

        assertEquals(0.4f, first.intensity, absoluteTolerance = 0.0001f)
        assertEquals(0.64f, second.intensity, absoluteTolerance = 0.0001f)
        assertEquals(0.596f, omega.value, absoluteTolerance = 0.0001f)
    }

    @Test
    fun `vector write applies llm delta relative to pre ticked snapshot`() = runTest {
        val store = InMemorySessionStateStore(
            SessionState(
                sessionId = "QQ-Group:1",
                vector = BioVector.Neutral,
                origin = BioVector.Neutral,
                omega = OmegaState(0.0f),
                shockState = null,
                evolutionIndex = 0,
            ),
        )
        val service = VectorWriteService(store)
        val preTicked = BioVector.Neutral.copy(p = 0.3f)

        val result = service.applyLlmDelta(
            sessionId = "QQ-Group:1",
            preTickedSnapshot = preTicked,
            delta = VectorDelta(p = -0.1f, f = 0.2f),
        )

        assertEquals(0.2f, result.state.vector.p, absoluteTolerance = 0.0001f)
        assertEquals(0.7f, result.state.vector.f, absoluteTolerance = 0.0001f)
        assertEquals(1, result.state.evolutionIndex)
        assertContains(result.traceTags, TraceTag.VectorWriteSerialized)
    }
}

private class InMemorySessionStateStore(initial: SessionState) : SessionStateStore {
    private var state = initial

    override suspend fun read(sessionId: String): SessionState {
        assertEquals(state.sessionId, sessionId)
        return state
    }

    override suspend fun write(state: SessionState) {
        this.state = state
    }

    override suspend fun sessionIds(): Set<String> = setOf(state.sessionId)
}
