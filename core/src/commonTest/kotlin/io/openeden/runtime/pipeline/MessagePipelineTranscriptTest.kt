package io.openeden.runtime.pipeline

import io.openeden.bio.BioVector
import io.openeden.llm.LlmClient
import io.openeden.llm.LlmOutput
import io.openeden.persona.PersonaConfig
import io.openeden.persona.PersonaMode
import io.openeden.persona.PersonaSubState
import io.openeden.prompt.BuiltPrompt
import io.openeden.prompt.PromptSectionKeys
import io.openeden.runtime.session.MutableSessionStateStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MessagePipelineTranscriptTest {
    @Test
    fun `validated user turn publishes one public transcript record`() = runTest {
        val store = MutableSessionStateStore(activeIncarnationId = "incarnation-a")
        val pipeline = pipeline(store, ValidLlmClient(response = "validated response"))

        pipeline.handle(request(turnId = "client-turn-1"))

        val turn = store.page(50).turns.single()
        assertEquals("client-turn-1", turn.turnId)
        assertEquals("incarnation-a", turn.incarnationId)
        assertEquals("CLI:local", turn.sessionId)
        assertEquals("CLI", turn.platform)
        assertEquals("local", turn.scopeId)
        assertEquals("user-1", turn.userId)
        assertEquals("hello", turn.userText)
        assertEquals("validated response", turn.assistantText)
    }

    @Test
    fun `invalid user and heartbeat turns do not enter public transcript`() = runTest {
        val store = MutableSessionStateStore(activeIncarnationId = "incarnation-a")

        pipeline(store, InvalidLlmClient).handle(request(turnId = "invalid-user"))
        pipeline(store, ValidLlmClient()).handle(
            request(turnId = "heartbeat-1").copy(source = TurnSource.HEARTBEAT),
        )

        assertTrue(store.page(50).turns.isEmpty())
        assertEquals(1L, store.read("CLI:local").evolutionIndex)
    }

    @Test
    fun `inference failure does not partially commit state or transcript`() = runTest {
        val store = MutableSessionStateStore(activeIncarnationId = "incarnation-a")
        val pipeline = pipeline(store, ThrowingLlmClient(IllegalStateException("inference failed")))

        assertFailsWith<IllegalStateException> {
            pipeline.handle(request(turnId = "failed-turn"))
        }

        assertEquals(BioVector.Neutral, store.read("CLI:local").vector)
        assertEquals(0L, store.read("CLI:local").evolutionIndex)
        assertTrue(store.page(50).turns.isEmpty())
    }

    @Test
    fun `cancellation does not partially commit state or transcript`() = runTest {
        val store = MutableSessionStateStore(activeIncarnationId = "incarnation-a")
        val pipeline = pipeline(store, ThrowingLlmClient(CancellationException("cancelled")))

        assertFailsWith<CancellationException> {
            pipeline.handle(request(turnId = "cancelled-turn"))
        }

        assertEquals(BioVector.Neutral, store.read("CLI:local").vector)
        assertEquals(0L, store.read("CLI:local").evolutionIndex)
        assertTrue(store.page(50).turns.isEmpty())
    }

    @Test
    fun `retrying the same turn id does not evolve state twice`() = runTest {
        val store = MutableSessionStateStore(activeIncarnationId = "incarnation-a")
        var clock = 1_000L
        val pipeline = pipeline(store, ValidLlmClient(), nowMs = { clock })
        val request = request(turnId = "stable-retry-id")

        val firstResult = pipeline.handle(request)
        val firstTurn = store.page(50).turns.single()
        clock = 2_000L
        val retryResult = pipeline.handle(request)

        assertEquals(1L, store.read("CLI:local").evolutionIndex)
        assertEquals(1, store.page(50).turns.size)
        assertEquals(1_000L, firstTurn.completedAtMs)
        assertEquals(firstTurn.completedAtMs, store.page(50).turns.single().completedAtMs)
        assertEquals(firstResult.updatedVector, retryResult.updatedVector)
        assertEquals(store.read("CLI:local").vector, retryResult.updatedVector)
        assertEquals(1L, retryResult.evolutionIndex)
    }

    private fun pipeline(
        store: MutableSessionStateStore,
        llmClient: LlmClient,
        nowMs: () -> Long = { 1_000L },
    ) = DevelopmentMessagePipeline.create(
        personaConfig = persona(),
        store = store,
        llmClient = llmClient,
        transcriptStore = store,
        nowMs = nowMs,
    )

    private fun request(turnId: String) = DevelopmentMessageRequest(
        turnId = turnId,
        platform = "CLI",
        scopeId = "local",
        userId = "user-1",
        text = "hello",
        emotionConfidence = 0.49f,
    )

    private fun persona() = PersonaConfig(
        mode = PersonaMode.GROWTH,
        startSubState = PersonaSubState.PRE_COMMAND,
        promptSections = mapOf(
            PromptSectionKeys.PersonaBase to "base",
            PromptSectionKeys.OutputLayerRules to "rules",
            PromptSectionKeys.PreCommandPatch to "pre",
            PromptSectionKeys.TrueSelfPatch to "true",
            PromptSectionKeys.AwakenedPatch to "awake",
            PromptSectionKeys.Heartbeat to "heartbeat",
            PromptSectionKeys.ShockHeartbeat to "shock heartbeat",
        ),
    )

    private class ValidLlmClient(
        private val response: String = "response",
    ) : LlmClient {
        override suspend fun complete(prompt: BuiltPrompt) = LlmOutput(
            internalLogic = "logic",
            vectorDelta = mapOf(
                "L" to 0.1f,
                "P" to 0.0f,
                "E" to 0.0f,
                "S" to 0.0f,
                "tau" to 0.0f,
                "V" to 0.0f,
                "M" to 0.0f,
                "F" to 0.0f,
            ),
            response = response,
        )
    }

    private object InvalidLlmClient : LlmClient {
        override suspend fun complete(prompt: BuiltPrompt) = LlmOutput(
            internalLogic = "logic",
            vectorDelta = mapOf("L" to 0.1f),
            response = "invalid",
        )
    }

    private class ThrowingLlmClient(
        private val failure: Throwable,
    ) : LlmClient {
        override suspend fun complete(prompt: BuiltPrompt): LlmOutput = throw failure
    }
}
