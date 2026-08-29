package io.openeden.runtime.session

import io.openeden.runtime.pipeline.DevelopmentMessagePipeline
import io.openeden.runtime.pipeline.DevelopmentMessageRequest
import io.openeden.runtime.incarnation.IncarnationState
import io.openeden.runtime.incarnation.IncarnationStateStore
import io.openeden.runtime.incarnation.MutableIncarnationStateStore
import io.openeden.runtime.state.VectorWriteService


import io.openeden.llm.LlmClient
import io.openeden.llm.LlmOutput
import io.openeden.persona.PersonaConfig
import io.openeden.persona.PersonaMode
import io.openeden.persona.PersonaSubState
import io.openeden.prompt.BuiltPrompt
import io.openeden.prompt.PromptSectionKeys
import io.openeden.transcript.InMemoryTranscriptStore
import io.openeden.transcript.ConversationTurn
import io.openeden.transcript.TranscriptStore
import io.openeden.transcript.TurnCommitOutcome
import io.openeden.trace.TraceTag
import io.openeden.bio.BioVector
import io.openeden.bio.VectorDelta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest

class TurnCoordinatorConcurrencyTest {
    @Test
    fun `concurrent deltas from different scopes accumulate on one incarnation`() = runTest {
        val fixture = GlobalIncarnationPipelineFixture()
        (1..100).map { index ->
            async {
                fixture.send(
                    turnId = "turn-$index",
                    sessionId = if (index % 2 == 0) "QQ:group-a" else "WEB:user-a",
                    text = "positive-$index",
                )
            }
        }.awaitAll()

        assertEquals(100L, fixture.state().evolutionIndex)
        assertTrue(fixture.state().vector.p in 0.5f..<0.99f)
        assertEquals(1, fixture.maximumConcurrentBioWrites)
    }

    @Test
    fun `concurrent first turns initialize one incarnation through its gate`() = runTest {
        val fixture = GlobalIncarnationPipelineFixture()

        coroutineScope {
            launch { fixture.send("turn-a", "QQ:group-a", "a") }
            launch { fixture.send("turn-b", "WEB:user-a", "b") }
        }

        assertEquals(1, fixture.maximumConcurrentBioInitializations)
    }

    @Test
    fun `same session turns serialize the whole stateful flow`() = runTest {
        val store = MutableSessionStateStore()
        val incarnationStore = MutableIncarnationStateStore(transcriptStore = store.transcript)
        val completionMutex = Mutex()
        var concurrentCompletions = 0
        var maximumConcurrentCompletions = 0
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = testPersonaConfig(),
            store = store,
            incarnationStateStore = incarnationStore,
            llmClient = object : LlmClient {
                override suspend fun complete(prompt: BuiltPrompt): LlmOutput {
                    completionMutex.withLock {
                        concurrentCompletions += 1
                        maximumConcurrentCompletions = maxOf(maximumConcurrentCompletions, concurrentCompletions)
                    }
                    try {
                        delay(1)
                        return LlmOutput(
                            internalLogic = "serialized test turn references HEURISTIC_FALLBACK",
                            vectorDelta = mapOf(
                                "L" to 0.0f,
                                "P" to 0.01f,
                                "E" to 0.0f,
                                "S" to 0.0f,
                                "tau" to 0.0f,
                                "V" to 0.0f,
                                "M" to 0.0f,
                                "F" to 0.0f,
                            ),
                            response = "ok",
                        )
                    } finally {
                        completionMutex.withLock { concurrentCompletions -= 1 }
                    }
                }
            },
        )

        val results = (1..100).map { index ->
            async {
                pipeline.handle(
                    DevelopmentMessageRequest(
                        turnId = "turn-$index",
                        platform = "TEST",
                        scopeId = "shared",
                        userId = "u$index",
                        text = "turn-$index",
                        emotionConfidence = 0.49f,
                    ),
                )
            }
        }.awaitAll()

        assertEquals((1L..100L).toSet(), results.map { it.evolutionIndex }.toSet())
        assertEquals(1, maximumConcurrentCompletions)
        val state = incarnationStore.read("development")
        assertEquals(100L, state.evolutionIndex)
        assertTrue(state.vector.p in 0.5f..<0.99f)
    }

    @Test
    fun `stale scope centroid cannot overwrite newer gated origin`() = runTest {
        val sessions = MutableSessionStateStore()
        val incarnations = MutableIncarnationStateStore(transcriptStore = sessions.transcript)
        val initial = incarnations.readOrCreate(
            incarnationId = "development",
            personaMode = PersonaMode.GROWTH,
            personaStartSubState = PersonaSubState.PRE_COMMAND,
        ).copy(origin = BioVector.Neutral.copy(p = 0.2f))
        incarnations.write(initial)
        val writer = VectorWriteService(incarnationStore = incarnations)
        val newerOrigin = BioVector.Neutral.copy(p = 0.7f)
        val newerPersisted = CompletableDeferred<Unit>()

        val staleScope = async {
            newerPersisted.await()
            writer.commitIncarnationTurn(
                incarnationId = initial.incarnationId,
                baseSnapshot = initial.vector,
                preTickedSnapshot = initial.vector,
                delta = VectorDelta.Zero,
                shockSignal = null,
                lastUserActivityMs = null,
                reductionAtMs = 1_000L,
            )
        }
        val newerScope = async {
            writer.updateIncarnation(initial.incarnationId) { it.copy(origin = newerOrigin) }
            newerPersisted.complete(Unit)
        }

        newerScope.await()
        staleScope.await()

        assertEquals(newerOrigin, incarnations.read(initial.incarnationId).origin)
    }

    @Test
    fun `delayed same evolution centroid candidate cannot overwrite newer revision`() = runTest {
        val incarnations = MutableIncarnationStateStore()
        val initial = incarnations.readOrCreate(
            incarnationId = "development",
            personaMode = PersonaMode.GROWTH,
            personaStartSubState = PersonaSubState.PRE_COMMAND,
        )
        val writer = VectorWriteService(incarnationStore = incarnations)
        val delayedRevision = writer.reserveCentroidRevision(initial.incarnationId)
        val newerRevision = writer.reserveCentroidRevision(initial.incarnationId)
        val newerOrigin = BioVector.Neutral.copy(p = 0.7f)

        val accepted = writer.applyCentroidCandidate(initial.incarnationId, newerRevision, newerOrigin)
        val rejected = writer.applyCentroidCandidate(
            initial.incarnationId,
            delayedRevision,
            BioVector.Neutral.copy(p = 0.2f),
        )

        assertTrue(accepted.traceTags.contains(TraceTag.CentroidUpdated))
        assertTrue(rejected.traceTags.isEmpty())
        assertEquals(newerOrigin, rejected.state.origin)
        assertEquals(newerRevision, rejected.state.centroidRevision)
        assertEquals(newerRevision, rejected.state.originRevision)
        assertEquals(0L, rejected.state.evolutionIndex)
    }

    @Test
    fun `concurrent scopes at same evolution preserve newest centroid revision`() = runTest {
        val incarnations = MutableIncarnationStateStore()
        val initial = incarnations.readOrCreate(
            incarnationId = "development",
            personaMode = PersonaMode.GROWTH,
            personaStartSubState = PersonaSubState.PRE_COMMAND,
        )
        val writer = VectorWriteService(incarnationStore = incarnations)
        val firstRevision = writer.reserveCentroidRevision(initial.incarnationId)
        val secondRevision = writer.reserveCentroidRevision(initial.incarnationId)
        val newerApplied = CompletableDeferred<Unit>()
        val newerOrigin = BioVector.Neutral.copy(e = 0.8f)

        val delayedScope = async {
            newerApplied.await()
            writer.applyCentroidCandidate(
                initial.incarnationId,
                firstRevision,
                BioVector.Neutral.copy(e = 0.1f),
            )
        }
        val newerScope = async {
            writer.applyCentroidCandidate(initial.incarnationId, secondRevision, newerOrigin).also {
                newerApplied.complete(Unit)
            }
        }

        newerScope.await()
        delayedScope.await()
        val persisted = incarnations.read(initial.incarnationId)
        assertEquals(newerOrigin, persisted.origin)
        assertEquals(secondRevision, persisted.originRevision)
        assertEquals(0L, persisted.evolutionIndex)
    }

    @Test
    fun `stale heartbeat centroid cannot overwrite newer user candidate`() = runTest {
        val incarnations = MutableIncarnationStateStore()
        val initial = incarnations.readOrCreate(
            incarnationId = "development",
            personaMode = PersonaMode.GROWTH,
            personaStartSubState = PersonaSubState.PRE_COMMAND,
        )
        val writer = VectorWriteService(incarnationStore = incarnations)
        val heartbeatRevision = writer.reserveCentroidRevision(initial.incarnationId)
        val userRevision = writer.reserveCentroidRevision(initial.incarnationId)
        val userOrigin = BioVector.Neutral.copy(v = 0.65f)

        writer.applyCentroidCandidate(initial.incarnationId, userRevision, userOrigin)
        val heartbeatResult = writer.applyCentroidCandidate(
            initial.incarnationId,
            heartbeatRevision,
            BioVector.Neutral.copy(v = 0.25f),
        )

        assertEquals(userOrigin, heartbeatResult.state.origin)
        assertEquals(userRevision, heartbeatResult.state.originRevision)
    }

    private fun testPersonaConfig(): PersonaConfig = PersonaConfig(
        mode = PersonaMode.GROWTH,
        startSubState = PersonaSubState.PRE_COMMAND,
        promptSections = mapOf(
            PromptSectionKeys.PersonaBase to "base",
            PromptSectionKeys.OutputLayerRules to "rules",
            PromptSectionKeys.PreCommandPatch to "pre",
            PromptSectionKeys.TrueSelfPatch to "true",
            PromptSectionKeys.AwakenedPatch to "awake",
            PromptSectionKeys.Heartbeat to "heartbeat",
            PromptSectionKeys.ShockHeartbeat to "shock",
        ),
    )
}

private class GlobalIncarnationPipelineFixture {
    private val sessionStore = MutableSessionStateStore()
    private val store = TrackingIncarnationStateStore(sessionStore.transcript)
    private val pipeline = DevelopmentMessagePipeline.create(
        personaConfig = PersonaConfig(
            mode = PersonaMode.GROWTH,
            startSubState = PersonaSubState.PRE_COMMAND,
            promptSections = mapOf(
                PromptSectionKeys.PersonaBase to "base",
                PromptSectionKeys.OutputLayerRules to "rules",
                PromptSectionKeys.PreCommandPatch to "pre",
                PromptSectionKeys.TrueSelfPatch to "true",
                PromptSectionKeys.AwakenedPatch to "awake",
                PromptSectionKeys.Heartbeat to "heartbeat",
                PromptSectionKeys.ShockHeartbeat to "shock",
            ),
        ),
        store = sessionStore,
        incarnationStateStore = store,
        transcriptStore = sessionStore.transcript,
        llmClient = object : LlmClient {
            override suspend fun complete(prompt: BuiltPrompt): LlmOutput {
                delay(1)
                return LlmOutput(
                    internalLogic = "concurrent incarnation turn references HEURISTIC_FALLBACK",
                    vectorDelta = mapOf(
                        "L" to 0.0f,
                        "P" to 0.01f,
                        "E" to 0.0f,
                        "S" to 0.0f,
                        "tau" to 0.0f,
                        "V" to 0.0f,
                        "M" to 0.0f,
                        "F" to 0.0f,
                    ),
                    response = "ok",
                )
            }
        },
    )

    val maximumConcurrentBioWrites: Int
        get() = store.maximumConcurrentWrites

    val maximumConcurrentBioInitializations: Int
        get() = store.maximumConcurrentInitializations

    suspend fun send(turnId: String, sessionId: String, text: String) {
        val (platform, scopeId) = sessionId.split(':', limit = 2)
        pipeline.handle(
            DevelopmentMessageRequest(
                turnId = turnId,
                platform = platform,
                scopeId = scopeId,
                userId = "user-$scopeId",
                text = text,
                emotionConfidence = 0.49f,
            ),
        )
    }

    suspend fun state() = store.read("development")
}

private class TrackingIncarnationStateStore(transcriptStore: InMemoryTranscriptStore) : IncarnationStateStore {
    private val delegate = MutableIncarnationStateStore(transcriptStore = transcriptStore)
    private val measurementMutex = Mutex()
    private var concurrentWrites = 0
    private var maximumWrites = 0
    private var concurrentInitializations = 0
    private var maximumInitializations = 0

    val maximumConcurrentWrites: Int
        get() = maximumWrites

    val maximumConcurrentInitializations: Int
        get() = maximumInitializations

    override suspend fun read(incarnationId: String): IncarnationState = delegate.read(incarnationId)

    override suspend fun readOrCreate(
        incarnationId: String,
        personaMode: PersonaMode,
        personaStartSubState: PersonaSubState,
    ): IncarnationState {
        measurementMutex.withLock {
            concurrentInitializations += 1
            maximumInitializations = maxOf(maximumInitializations, concurrentInitializations)
        }
        try {
            delay(1)
            return delegate.readOrCreate(incarnationId, personaMode, personaStartSubState)
        } finally {
            measurementMutex.withLock { concurrentInitializations -= 1 }
        }
    }

    override suspend fun write(state: IncarnationState) {
        trackWrite { delegate.write(state) }
    }

    override suspend fun writeCommittedTurn(
        state: IncarnationState,
        turn: ConversationTurn,
        postCommitPlan: io.openeden.transcript.TurnPostCommitPlan,
    ): TurnCommitOutcome = trackWrite { delegate.writeCommittedTurn(state, turn, postCommitPlan) }

    private suspend fun <T> trackWrite(block: suspend () -> T): T {
        measurementMutex.withLock {
            concurrentWrites += 1
            maximumWrites = maxOf(maximumWrites, concurrentWrites)
        }
        try {
            delay(1)
            return block()
        } finally {
            measurementMutex.withLock { concurrentWrites -= 1 }
        }
    }

    override fun commitsTo(transcriptStore: TranscriptStore): Boolean = delegate.commitsTo(transcriptStore)

}
