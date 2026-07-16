package io.openeden.runtime.pipeline

import io.openeden.bio.BioVector
import io.openeden.llm.LlmClient
import io.openeden.llm.LlmOutput
import io.openeden.memory.InMemoryMemoryPalace
import io.openeden.memory.MemoryStore
import io.openeden.memory.RetrievalRequest
import io.openeden.memory.RetrievalResult
import io.openeden.persona.PersonaConfig
import io.openeden.persona.PersonaMode
import io.openeden.persona.PersonaSubState
import io.openeden.prompt.BuiltPrompt
import io.openeden.prompt.PromptSectionKeys
import io.openeden.relationship.InMemoryRelationshipStateStore
import io.openeden.runtime.diary.SessionDiaryQueue
import io.openeden.runtime.inference.DirectInferenceExecutor
import io.openeden.runtime.session.MutableSessionStateStore
import io.openeden.runtime.session.SessionState
import io.openeden.runtime.session.SessionStateStore
import io.openeden.runtime.state.HomeostasisCentroidProvider
import io.openeden.trace.TraceTag
import io.openeden.transcript.AtomicTurnCommitStore
import io.openeden.transcript.ActiveIncarnation
import io.openeden.transcript.ConversationHistoryPage
import io.openeden.transcript.ConversationTurn
import io.openeden.transcript.HistoryCursor
import io.openeden.transcript.InMemoryTranscriptStore
import io.openeden.transcript.TranscriptStore
import io.openeden.transcript.TurnCommitOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessagePipelineTranscriptTest {
    @Test
    fun `validated user turn publishes one public transcript record`() = runTest {
        val transcripts = InMemoryTranscriptStore("incarnation-a")
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = persona(),
            llmClient = ValidLlmClient(response = "validated response"),
            transcriptStore = transcripts,
        )

        pipeline.handle(request(turnId = "client-turn-1"))

        val turn = transcripts.page(50).turns.single()
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
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = persona(),
            store = store,
            llmClient = ThrowingLlmClient(IllegalStateException("inference failed")),
            transcriptStore = store,
            centroidProvider = HomeostasisCentroidProvider { BioVector.Neutral.copy(l = 0.2f) },
        )

        assertFailsWith<IllegalStateException> {
            pipeline.handle(request(turnId = "failed-turn"))
        }

        assertEquals(BioVector.Neutral, store.read("CLI:local").vector)
        assertEquals(BioVector.Neutral, store.read("CLI:local").origin)
        assertEquals(0L, store.read("CLI:local").evolutionIndex)
        assertTrue(store.page(50).turns.isEmpty())
    }

    @Test
    fun `invalid output does not persist the inference centroid`() = runTest {
        val store = MutableSessionStateStore(activeIncarnationId = "incarnation-a")
        val centroids = listOf(
            BioVector.Neutral.copy(p = 0.2f),
            BioVector.Neutral.copy(p = 0.3f),
        )
        var centroidCalls = 0
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = persona(),
            store = store,
            llmClient = InvalidLlmClient,
            transcriptStore = store,
            centroidProvider = HomeostasisCentroidProvider { centroids[centroidCalls++] },
        )

        pipeline.handle(request(turnId = "invalid-centroid"))

        assertEquals(BioVector.Neutral, store.read("CLI:local").origin)
        assertEquals(0L, store.read("CLI:local").evolutionIndex)
        assertEquals(1, centroidCalls)
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
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `retrying the same turn id does not evolve state twice`() = runTest {
        val transcripts = InMemoryTranscriptStore("incarnation-a")
        val stateStore = MutableSessionStateStore(transcriptStore = transcripts)
        val relationships = InMemoryRelationshipStateStore()
        val memoryPalace = InMemoryMemoryPalace(DirectInferenceExecutor)
        val retrievalOrigins = mutableListOf<BioVector>()
        val memories = object : MemoryStore by memoryPalace {
            override suspend fun retrieve(request: RetrievalRequest): RetrievalResult {
                retrievalOrigins += request.origin
                return memoryPalace.retrieve(request)
            }
        }
        val diaryQueue = SessionDiaryQueue()
        val diaryEvents = mutableListOf<io.openeden.runtime.diary.DiaryEvent>()
        backgroundScope.launch { diaryQueue.events().collect { diaryEvents += it } }
        runCurrent()
        var centroidCalls = 0
        val firstPreOrigin = BioVector.Neutral.copy(l = 0.4f)
        val firstPostOrigin = BioVector.Neutral.copy(l = 0.6f)
        val retryPreOrigin = BioVector.Neutral.copy(l = 0.2f)
        val centroids = listOf(firstPreOrigin, firstPostOrigin, retryPreOrigin)
        var clock = 1_000L
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = persona(),
            llmClient = ValidLlmClient(),
            store = stateStore,
            transcriptStore = transcripts,
            relationshipStore = relationships,
            memoryStore = memories,
            diaryQueue = diaryQueue,
            centroidProvider = HomeostasisCentroidProvider {
                centroids[centroidCalls++]
            },
            nowMs = { clock },
        )
        val request = request(turnId = "stable-retry-id")

        val firstResult = pipeline.handle(request)
        runCurrent()
        val firstTurn = transcripts.page(50).turns.single()
        val firstRelationship = relationships.readOrCreate("CLI:local", "user-1", clock)
        val firstMemories = memoryPalace.recent("CLI:local", 50)
        val firstDiaryCount = diaryEvents.size
        val firstCentroidCalls = centroidCalls
        val firstState = stateStore.read("CLI:local")
        assertTrue(firstRelationship.familiarity > 0.0f)
        assertTrue(firstMemories.isNotEmpty())
        assertEquals(1, firstDiaryCount)
        assertEquals(2, firstCentroidCalls)
        assertEquals(firstPostOrigin, firstState.origin)
        assertEquals(firstPreOrigin, retrievalOrigins.single())
        clock = 2_000L
        val retryResult = pipeline.handle(request)
        runCurrent()

        assertEquals(1, transcripts.page(50).turns.size)
        assertEquals(1_000L, firstTurn.completedAtMs)
        assertEquals(firstTurn.completedAtMs, transcripts.page(50).turns.single().completedAtMs)
        assertEquals(firstResult.updatedVector, retryResult.updatedVector)
        assertEquals(1L, stateStore.read("CLI:local").evolutionIndex)
        assertEquals(firstState, stateStore.read("CLI:local"))
        assertEquals(stateStore.read("CLI:local").vector, retryResult.updatedVector)
        assertEquals(1L, retryResult.evolutionIndex)
        assertEquals(firstRelationship, relationships.readOrCreate("CLI:local", "user-1", clock))
        assertEquals(firstMemories, memoryPalace.recent("CLI:local", 50))
        assertEquals(firstDiaryCount, diaryEvents.size)
        assertEquals(firstCentroidCalls + 1, centroidCalls)
        assertEquals(retryPreOrigin, retrievalOrigins.last())
        assertContains(retryResult.traceTags, TraceTag.TranscriptRetry)
        assertFalse(TraceTag.VectorWriteSerialized in retryResult.traceTags)
        assertFalse(TraceTag.ShockStateTransition in retryResult.traceTags)
    }

    @Test
    fun `explicit mutable state and different transcript stores are rejected`() {
        val stateStore = MutableSessionStateStore(activeIncarnationId = "incarnation-a")
        val transcripts = InMemoryTranscriptStore("incarnation-a")

        assertFailsWith<IllegalArgumentException> {
            DevelopmentMessagePipeline.create(
                personaConfig = persona(),
                store = stateStore,
                transcriptStore = transcripts,
            )
        }
    }

    @Test
    fun `unrelated atomic state and transcript stores are rejected despite matching incarnation`() {
        val unrelatedAtomicStore = object :
            SessionStateStore by MutableSessionStateStore(activeIncarnationId = "incarnation-a"),
            AtomicTurnCommitStore {
            override fun commitsTo(transcriptStore: TranscriptStore): Boolean = false

            override suspend fun writeCommittedTurn(
                state: SessionState,
                turn: ConversationTurn,
            ) = TurnCommitOutcome.INSERTED
        }
        val transcripts = InMemoryTranscriptStore("incarnation-a")

        assertFailsWith<IllegalArgumentException> {
            DevelopmentMessagePipeline.create(
                personaConfig = persona(),
                store = unrelatedAtomicStore,
                transcriptStore = transcripts,
            )
        }
    }

    @Test
    fun `non-memory transcript without co-backed state is rejected before callbacks`() {
        var callbacks = 0
        val callbackTranscript = object : TranscriptStore {
            override suspend fun activeIncarnation(): ActiveIncarnation {
                callbacks++
                return ActiveIncarnation("incarnation-a", 0L)
            }

            override suspend fun append(turn: ConversationTurn) {
                callbacks++
            }

            override suspend fun page(limit: Int, before: HistoryCursor?): ConversationHistoryPage {
                callbacks++
                return ConversationHistoryPage(emptyList(), null, false)
            }
        }

        assertFailsWith<IllegalStateException> {
            DevelopmentMessagePipeline.create(
                personaConfig = persona(),
                transcriptStore = callbackTranscript,
            )
        }
        assertEquals(0, callbacks)
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
