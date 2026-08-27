package io.openeden.runtime.state

import io.openeden.runtime.affect.EmotionSignal
import io.openeden.runtime.affect.OmegaState
import io.openeden.runtime.affect.PreTickEngine
import io.openeden.runtime.affect.ShockState
import io.openeden.runtime.affect.ShockStateEngine
import io.openeden.runtime.affect.ShockSignal
import io.openeden.runtime.inference.RecordingInferenceExecutor
import io.openeden.runtime.session.MutableSessionStateStore
import io.openeden.runtime.session.SessionState
import io.openeden.runtime.session.SessionStateStore

import io.openeden.bio.BioVector
import io.openeden.bio.VectorDelta
import io.openeden.trace.TraceTag
import io.openeden.transcript.ConversationTurn
import io.openeden.transcript.InMemoryTranscriptStore
import io.openeden.transcript.TurnCommitOutcome
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class RuntimeInvariantTest {
    @Test
    fun `bio ownership is incarnation global while transcript ownership is conversation scoped`() {
        assertEquals(StateOwnership.INCARNATION, RuntimeInvariantConstants.bioStateOwnership)
        assertEquals(StateOwnership.CONVERSATION, RuntimeInvariantConstants.transcriptOwnership)
    }

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
        assertEquals(1.0f, highConfidence?.intensity)
    }

    @Test
    fun `active shock signal uses EMA and does not jump omega again`() = runTest {
        val store = InMemorySessionStateStore(
            SessionStateStore.neutral("QQ:active").copy(
                omega = OmegaState(0.2f),
                shockState = ShockState(
                    active = true,
                    intensity = 0.8f,
                    description = "old",
                    triggeredAt = Instant.fromEpochMilliseconds(10L),
                    decayLambda = 0.001f,
                    shockHeartbeatFired = true,
                ),
            ),
        )
        val result = VectorWriteService(store).applyShock(
            sessionId = "QQ:active",
            signal = ShockSignal(
                description = "new",
                intensity = 0.2f,
                decayLambda = 0.002f,
                triggeredAt = Instant.fromEpochMilliseconds(20L),
            ),
        )

        assertEquals(0.56f, assertNotNull(result.state.shockState).intensity, absoluteTolerance = 0.0001f)
        assertEquals(true, result.state.shockState?.shockHeartbeatFired)
        assertEquals(0.2f, result.state.omega.value, absoluteTolerance = 0.0001f)
        assertEquals(Instant.fromEpochMilliseconds(10L), result.state.shockState?.triggeredAt)
    }

    @Test
    fun `inactive shock signal activates once and jumps omega`() = runTest {
        val store = InMemorySessionStateStore(
            SessionStateStore.neutral("QQ:inactive").copy(
                omega = OmegaState(0.1f),
                shockState = ShockState(
                    active = false,
                    intensity = 0.0f,
                    description = "quiet",
                    triggeredAt = Instant.fromEpochMilliseconds(10L),
                    decayLambda = 0.001f,
                    shockHeartbeatFired = true,
                ),
            ),
        )
        val result = VectorWriteService(store).applyShock(
            sessionId = "QQ:inactive",
            signal = ShockSignal(
                description = "activation",
                intensity = 1.0f,
                decayLambda = 0.002f,
                triggeredAt = Instant.fromEpochMilliseconds(20L),
            ),
        )

        assertEquals(0.4f, assertNotNull(result.state.shockState).intensity, absoluteTolerance = 0.0001f)
        assertEquals(true, result.state.shockState?.active)
        assertEquals(false, result.state.shockState?.shockHeartbeatFired)
        assertEquals(0.16f, result.state.omega.value, absoluteTolerance = 0.0001f)
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

    @Test
    fun `commit turn vector math runs inside inference executor without shock signal`() = runTest {
        val store = InMemorySessionStateStore(
            SessionStateStore.neutral("QQ:commit-math"),
        )
        val executor = RecordingInferenceExecutor()
        val service = VectorWriteService(store, inferenceExecutor = executor)

        service.commitTurnLocked(
            sessionId = "QQ:commit-math",
            preTickedSnapshot = BioVector.Neutral.copy(p = 0.3f),
            originSnapshot = BioVector.Neutral,
            delta = VectorDelta(p = -0.1f),
            shock = null,
            lastUserActivityMs = null,
        )

        assertEquals(1, executor.calls)
    }

    @Test
    fun `background drift vector math runs inside inference executor`() = runTest {
        val store = InMemorySessionStateStore(
            SessionStateStore.neutral("QQ:background-drift"),
        )
        val executor = RecordingInferenceExecutor()
        val service = VectorWriteService(store, inferenceExecutor = executor)

        service.applyBackgroundDrift(
            sessionId = "QQ:background-drift",
            delta = VectorDelta(s = 0.1f),
        )

        assertEquals(1, executor.calls)
    }

    @Test
    fun `already committed turn preserves origin and reports only retry trace`() = runTest {
        val transcripts = InMemoryTranscriptStore("incarnation-a")
        val store = MutableSessionStateStore(transcriptStore = transcripts)
        val service = VectorWriteService(store)
        val sessionId = "CLI:local"
        val firstOrigin = BioVector.Neutral.copy(e = 0.6f)
        val turn = ConversationTurn(
            turnId = "stable-turn",
            incarnationId = "incarnation-a",
            sessionId = sessionId,
            platform = "CLI",
            scopeId = "local",
            userId = "user-1",
            userText = "hello",
            assistantText = "response",
            completedAtMs = 100L,
        )
        service.commitTurnLocked(
            sessionId = sessionId,
            preTickedSnapshot = BioVector.Neutral,
            originSnapshot = firstOrigin,
            delta = VectorDelta.Zero,
            shock = null,
            lastUserActivityMs = 100L,
            turn = turn,
        )
        val retryShock = ShockState(
            active = true,
            intensity = 0.9f,
            description = "retry shock",
            triggeredAt = Instant.fromEpochMilliseconds(200L),
            decayLambda = 0.01f,
        )

        val retry = service.commitTurnLocked(
            sessionId = sessionId,
            preTickedSnapshot = BioVector.Neutral,
            originSnapshot = BioVector.Neutral.copy(e = 0.1f),
            delta = VectorDelta.Zero,
            shock = retryShock,
            lastUserActivityMs = 200L,
            turn = turn.copy(completedAtMs = 200L),
        )

        assertEquals(TurnCommitOutcome.ALREADY_COMMITTED, retry.turnCommitOutcome)
        assertEquals(firstOrigin, retry.state.origin)
        assertNull(retry.state.shockState)
        assertEquals(setOf(TraceTag.TranscriptRetry), retry.traceTags)
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
