package io.openeden.runtime.pipeline

import io.openeden.bio.BioVector
import io.openeden.llm.LlmClient
import io.openeden.llm.LlmOutput
import io.openeden.memory.InMemoryMemoryPalace
import io.openeden.memory.MemoryContentFingerprint
import io.openeden.memory.MemoryExclusionContext
import io.openeden.memory.MemoryMetadata
import io.openeden.memory.MemoryStore
import io.openeden.memory.MemorySnippet
import io.openeden.memory.RetrievalMode
import io.openeden.memory.RetrievalRequest
import io.openeden.memory.RetrievalResult
import io.openeden.persona.PersonaConfig
import io.openeden.persona.PersonaMode
import io.openeden.persona.PersonaSubState
import io.openeden.prompt.BuiltPrompt
import io.openeden.prompt.DefaultPromptBuilder
import io.openeden.prompt.PromptBuilder
import io.openeden.prompt.PromptInput
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
    fun `prompt recent turns come from transcript instead of memory recency`() = runTest {
        val transcripts = InMemoryTranscriptStore("incarnation-a")
        repeat(3) { index ->
            transcripts.append(
                ConversationTurn(
                    turnId = "previous-turn-$index",
                    incarnationId = "incarnation-a",
                    sessionId = "CLI:local",
                    platform = "CLI",
                    scopeId = "local",
                    userId = "user-1",
                    userText = "previous user text $index",
                    assistantText = "previous assistant text $index",
                    completedAtMs = index.toLong() + 10L,
                ),
            )
        }
        val memoryPalace = InMemoryMemoryPalace(DirectInferenceExecutor)
        val ragRecentMemory = MemorySnippet(
            id = "rag-recent",
            content = "rag recent memory",
            metadata = MemoryMetadata(
                snapshot8D = BioVector.Neutral,
                omegaState = 0.0f,
                deltaVec = io.openeden.bio.VectorDelta.Zero,
                snapshotOrigin = BioVector.Neutral,
                userId = "user-1",
            ),
        )
        var memoryRecentCalls = 0
        val memoryStore = object : MemoryStore by memoryPalace {
            override suspend fun retrieve(request: RetrievalRequest): RetrievalResult = RetrievalResult(
                mode = RetrievalMode.CONGRUENT,
                injectionLabel = "[memory]",
                memories = emptyList(),
                recentMemories = listOf(ragRecentMemory),
            )

            override suspend fun recent(sessionId: String, limit: Int): List<io.openeden.memory.MemorySnippet> {
                memoryRecentCalls += 1
                return emptyList()
            }
        }
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = persona(),
            store = MutableSessionStateStore(transcriptStore = transcripts),
            transcriptStore = transcripts,
            memoryStore = memoryStore,
            llmClient = ValidLlmClient(),
            nowMs = { 100L },
        )

        val result = pipeline.handle(request(turnId = "current-turn"))

        assertContains(result.prompt.contextText, "previous user text 1")
        assertContains(result.prompt.contextText, "previous assistant text 2")
        assertFalse(result.prompt.contextText.contains("previous user text 0"))
        assertContains(result.prompt.contextText, "rag recent memory")
        assertEquals(0, memoryRecentCalls)
    }

    @Test
    fun `retrieval excludes transcript turns that will be injected into the prompt`() = runTest {
        val transcripts = InMemoryTranscriptStore("incarnation-a")
        repeat(3) { index ->
            transcripts.append(
                ConversationTurn(
                    turnId = "excluded-turn-$index",
                    incarnationId = "incarnation-a",
                    sessionId = "CLI:local",
                    platform = "CLI",
                    scopeId = "local",
                    userId = "user-1",
                    userText = "user $index",
                    assistantText = "assistant $index",
                    completedAtMs = index.toLong() + 10L,
                ),
            )
        }
        val delegate = InMemoryMemoryPalace(DirectInferenceExecutor)
        var capturedRequest: RetrievalRequest? = null
        val memoryStore = object : MemoryStore by delegate {
            override suspend fun retrieve(request: RetrievalRequest): RetrievalResult {
                capturedRequest = request
                return delegate.retrieve(request)
            }
        }

        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = persona(),
            store = MutableSessionStateStore(transcriptStore = transcripts),
            transcriptStore = transcripts,
            memoryStore = memoryStore,
            llmClient = ValidLlmClient(),
            nowMs = { 100L },
        )

        pipeline.handle(request(turnId = "current-turn"))

        assertEquals(
            setOf("excluded-turn-1", "excluded-turn-2"),
            capturedRequest?.exclusionContext?.sourceTurnIds,
        )
        assertEquals(emptySet(), capturedRequest?.exclusionContext?.sourceMemoryIds)
        assertEquals(emptySet(), capturedRequest?.exclusionContext?.contentFingerprints)
    }

    @Test
    fun `immediate reference keeps the latest four transcript turns`() = runTest {
        val transcripts = InMemoryTranscriptStore("incarnation-a")
        repeat(6) { index ->
            transcripts.append(
                ConversationTurn(
                    turnId = "reference-turn-$index",
                    incarnationId = "incarnation-a",
                    sessionId = "CLI:local",
                    platform = "CLI",
                    scopeId = "local",
                    userId = "user-1",
                    userText = "reference user text $index",
                    assistantText = "reference assistant text $index",
                    completedAtMs = index.toLong() + 10L,
                ),
            )
        }
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = persona(),
            store = MutableSessionStateStore(transcriptStore = transcripts),
            transcriptStore = transcripts,
            llmClient = ValidLlmClient(),
            nowMs = { 100L },
        )

        val result = pipeline.handle(request(turnId = "current-turn").copy(text = "刚才说了什么"))

        (2..5).forEach { index ->
            assertContains(result.prompt.contextText, "reference user text $index")
            assertContains(result.prompt.contextText, "reference assistant text $index")
        }
        assertFalse(result.prompt.contextText.contains("reference user text 0"))
        assertFalse(result.prompt.contextText.contains("reference user text 1"))
    }

    @Test
    fun `pipeline passes exact recent transcript slice to prompt builder`() = runTest {
        suspend fun seededTranscripts() = InMemoryTranscriptStore("incarnation-a").also { transcripts ->
            repeat(6) { index ->
                transcripts.append(
                    ConversationTurn(
                        turnId = "slice-turn-$index",
                        incarnationId = "incarnation-a",
                        sessionId = "CLI:local",
                        platform = "CLI",
                        scopeId = "local",
                        userId = "user-1",
                        userText = "slice user $index",
                        assistantText = "slice assistant $index",
                        completedAtMs = index.toLong() + 10L,
                    ),
                )
            }
        }
        val ordinaryTranscripts = seededTranscripts()
        val immediateTranscripts = seededTranscripts()
        val capturedInputs = mutableListOf<PromptInput>()
        val promptBuilder = object : PromptBuilder {
            override suspend fun build(input: PromptInput): BuiltPrompt {
                capturedInputs += input
                return DefaultPromptBuilder().build(input)
            }
        }
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = persona(),
            store = MutableSessionStateStore(transcriptStore = ordinaryTranscripts),
            transcriptStore = ordinaryTranscripts,
            promptBuilder = promptBuilder,
            llmClient = ValidLlmClient(),
            nowMs = { 100L },
        )
        val immediatePipeline = DevelopmentMessagePipeline.create(
            personaConfig = persona(),
            store = MutableSessionStateStore(transcriptStore = immediateTranscripts),
            transcriptStore = immediateTranscripts,
            promptBuilder = promptBuilder,
            llmClient = ValidLlmClient(),
            nowMs = { 100L },
        )

        pipeline.handle(request(turnId = "ordinary-slice"))
        immediatePipeline.handle(request(turnId = "reference-slice").copy(text = "刚才说了什么"))

        assertEquals(
            listOf("slice-turn-4", "slice-turn-5"),
            capturedInputs[0].recentTurns.map { it.turnId },
        )
        assertEquals(
            listOf("slice-turn-2", "slice-turn-3", "slice-turn-4", "slice-turn-5"),
            capturedInputs[1].recentTurns.map { it.turnId },
        )
    }

    @Test
    fun `transcript recent failure degrades to empty context without blocking the turn`() = runTest {
        val transcripts = FailingRecentTranscriptStore()
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = persona(),
            store = transcripts,
            transcriptStore = transcripts,
            llmClient = ValidLlmClient(),
            nowMs = { 100L },
        )

        val result = pipeline.handle(request(turnId = "transcript-degraded"))

        assertTrue(TraceTag.TranscriptDegraded in result.traceTags)
        assertContains(result.prompt.contextText, "\"recent_turns\": []")
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
        assertEquals(listOf("stable-retry-id"), firstMemories.single().metadata.lineage.sourceTurnIds)
        assertEquals(
            MemoryContentFingerprint.of("user=user-1\ninput=hello\nresponse=response"),
            firstMemories.single().metadata.contentFingerprint,
        )
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
            internalLogic = "logic references HEURISTIC_FALLBACK",
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

    private class FailingRecentTranscriptStore : SessionStateStore, AtomicTurnCommitStore, TranscriptStore {
        private val delegate = MutableSessionStateStore(activeIncarnationId = "incarnation-a")

        override suspend fun read(sessionId: String): SessionState = delegate.read(sessionId)

        override suspend fun readOrCreate(
            sessionId: String,
            personaMode: PersonaMode?,
            personaStartSubState: PersonaSubState?,
        ): SessionState = delegate.readOrCreate(sessionId, personaMode, personaStartSubState)

        override suspend fun write(state: SessionState) = delegate.write(state)

        override suspend fun sessionIds(): Set<String> = delegate.sessionIds()

        override fun commitsTo(transcriptStore: TranscriptStore): Boolean = transcriptStore === this

        override suspend fun writeCommittedTurn(
            state: SessionState,
            turn: ConversationTurn,
        ): TurnCommitOutcome = delegate.writeCommittedTurn(state, turn)

        override suspend fun activeIncarnation(): ActiveIncarnation = delegate.activeIncarnation()

        override suspend fun append(turn: ConversationTurn) = delegate.append(turn)

        override suspend fun recentForSession(sessionId: String, limit: Int): List<ConversationTurn> =
            error("recent transcript unavailable")

        override suspend fun page(limit: Int, before: HistoryCursor?): ConversationHistoryPage =
            delegate.page(limit, before)
    }

    private object InvalidLlmClient : LlmClient {
        override suspend fun complete(prompt: BuiltPrompt) = LlmOutput(
            internalLogic = "logic references HEURISTIC_FALLBACK",
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
