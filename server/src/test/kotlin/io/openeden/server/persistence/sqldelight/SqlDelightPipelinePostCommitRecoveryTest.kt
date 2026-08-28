package io.openeden.server.persistence.sqldelight

import io.openeden.bio.BioVector
import io.openeden.llm.LlmClient
import io.openeden.llm.LlmOutput
import io.openeden.memory.InMemoryMemoryPalace
import io.openeden.persona.PersonaConfig
import io.openeden.persona.PersonaMode
import io.openeden.persona.PersonaSubState
import io.openeden.prompt.BuiltPrompt
import io.openeden.prompt.PromptSectionKeys
import io.openeden.relationship.RelationshipEvaluation
import io.openeden.relationship.RelationshipEvent
import io.openeden.relationship.RelationshipEventEvaluator
import io.openeden.relationship.RelationshipEventType
import io.openeden.relationship.RelationshipStateStore
import io.openeden.relationship.RelationshipTurn
import io.openeden.runtime.diary.DiaryCheckpoint
import io.openeden.runtime.diary.DiaryTask
import io.openeden.runtime.diary.DiaryTaskStatus
import io.openeden.runtime.diary.DiaryTaskStore
import io.openeden.runtime.diary.DiaryTriggerCoordinator
import io.openeden.runtime.pipeline.DevelopmentMessagePipeline
import io.openeden.runtime.pipeline.DevelopmentMessageRequest
import io.openeden.runtime.inference.DirectInferenceExecutor
import io.openeden.runtime.session.MutableSessionStateStore
import io.openeden.runtime.state.HomeostasisCentroidProvider
import io.openeden.trace.TraceTag
import io.openeden.transcript.TurnPostCommitStage.CENTROID
import io.openeden.transcript.TurnPostCommitStage.DIARY
import io.openeden.transcript.TurnPostCommitStage.RAW_MEMORY
import io.openeden.transcript.TurnPostCommitStage.RELATIONSHIP
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SqlDelightPipelinePostCommitRecoveryTest {
    private val tempDir = Files.createTempDirectory("openeden-sql-pipeline-recovery-test")
    private val dbPath = tempDir.resolve("openeden.db")

    @AfterTest
    fun cleanup() {
        Files.walk(tempDir).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    @Test
    fun `cancel after durable diary enqueue reopens and recovers SQL relationship and diary effects exactly once`() = runTest {
        val request = DevelopmentMessageRequest(
            turnId = "sql-recovery-turn",
            platform = "CLI",
            scopeId = "local",
            userId = "user-1",
            text = "hello",
            emotionConfidence = 0.49f,
        )
        val first = openStores()
        val cancellingDiary = object : DiaryTaskStore by first.diary {
            private var cancelled = false

            override suspend fun enqueueIfAbsent(task: DiaryTask): Set<String> {
                val tags = first.diary.enqueueIfAbsent(task)
                if (!cancelled) {
                    cancelled = true
                    throw CancellationException("cancel after durable SQL diary enqueue")
                }
                return tags
            }
        }
        val firstPipeline = pipeline(
            stores = first,
            llmClient = ValidLlmClient,
            diaryTaskStore = cancellingDiary,
        )

        assertFailsWith<CancellationException> { firstPipeline.handle(request) }
        val incarnationId = first.transcript.activeIncarnation().id
        val rawMemory = first.memory.recent("CLI:local", 50).single()
        val diaryTaskId = DiaryTriggerCoordinator.taskId("CLI:local", "vector_delta", rawMemory.id)
        val relationshipEvent = first.relationship.events(incarnationId, "CLI:user-1").single()
        val relationshipState = first.relationship.readOrCreate(incarnationId, "CLI:user-1")
        val durableDiaryTask = assertNotNull(first.diary.readById(diaryTaskId))
        val interruptedPostCommit = assertNotNull(first.transcript.postCommitState(request.turnId))

        assertEquals(1L, first.incarnation.read(incarnationId).evolutionIndex)
        assertEquals(RelationshipEventType.PREFERENCE_RESPECTED, relationshipEvent.type)
        assertEquals(request.turnId, relationshipEvent.sourceTurnId)
        assertEquals(1L, relationshipState.evidenceCount)
        assertEquals(1L, first.diary.countActive("CLI:local"))
        assertEquals(rawMemory.id, durableDiaryTask.sourceMemoryId)
        assertEquals(DiaryTaskStatus.PENDING, durableDiaryTask.status)
        assertEquals(setOf(RELATIONSHIP, RAW_MEMORY), interruptedPostCommit.completedStages)
        assertNull(first.diary.readCheckpoint("CLI:local"))
        first.close()

        val reopened = openStores()
        try {
            assertEquals(1, reopened.relationship.events(incarnationId, "CLI:user-1").size)
            assertEquals(1L, reopened.relationship.readOrCreate(incarnationId, "CLI:user-1").evidenceCount)
            assertEquals(durableDiaryTask, reopened.diary.readById(diaryTaskId))
            assertNull(reopened.diary.readCheckpoint("CLI:local"))

            val replay = pipeline(
                stores = reopened,
                llmClient = ThrowingLlmClient,
            ).handle(request)
            val recovered = checkNotNull(reopened.transcript.postCommitState(request.turnId))

            assertEquals("response", replay.response)
            assertEquals(1L, replay.evolutionIndex)
            assertEquals(1L, reopened.incarnation.read(incarnationId).evolutionIndex)
            assertEquals(1, reopened.memory.recent("CLI:local", 50).size)
            assertEquals(1, reopened.relationship.events(incarnationId, "CLI:user-1").size)
            assertEquals(1L, reopened.diary.countActive("CLI:local"))
            assertEquals(recovered.plan.requiredStages.toSet(), recovered.completedStages)
            assertEquals(setOf(RELATIONSHIP, RAW_MEMORY, DIARY, CENTROID), recovered.completedStages)
            assertContains(replay.traceTags, TraceTag.TranscriptRetry)

            val leased = assertNotNull(reopened.diary.leaseNext("CLI:local", 1_000L, 1_000L))
            val checkpoint = DiaryCheckpoint(rawMemory.id, 2_000L, "narrative-1")
            reopened.diary.completeWithCheckpoint(
                leased.id,
                assertNotNull(leased.leaseToken),
                checkpoint,
            )
            assertEquals(checkpoint, reopened.diary.readCheckpoint("CLI:local"))
        } finally {
            reopened.close()
        }

        val verified = openStores()
        try {
            assertEquals(1, verified.relationship.events(incarnationId, "CLI:user-1").size)
            assertEquals(DiaryTaskStatus.DONE, verified.diary.readById(diaryTaskId)?.status)
            assertEquals(
                DiaryCheckpoint(rawMemory.id, 2_000L, "narrative-1"),
                verified.diary.readCheckpoint("CLI:local"),
            )
        } finally {
            verified.close()
        }
    }

    @Test
    fun `transient SQL relationship failure retries persisted evaluation in a fresh pipeline without duplicate writes`() = runTest {
        val request = DevelopmentMessageRequest(
            turnId = "sql-relationship-retry",
            platform = "CLI",
            scopeId = "local",
            userId = "user-1",
            text = "hello",
            emotionConfidence = 0.49f,
        )
        val transcript = SqlDelightTranscriptStore.open(dbPath)
        val incarnation = SqlDelightIncarnationStateStore.open(
            dbPath = dbPath,
            ioDispatcher = newSqliteDispatcher("sql-relationship-recovery-incarnation-test"),
            committedTranscriptStore = transcript,
        )
        val relationship = SqlDelightRelationshipStateStore.open(dbPath)
        val memory = InMemoryMemoryPalace(DirectInferenceExecutor)
        var llmCalls = 0
        var evaluatorCalls = 0
        val firstLlm = object : LlmClient {
            override suspend fun complete(prompt: BuiltPrompt): LlmOutput {
                llmCalls += 1
                return ValidLlmClient.complete(prompt)
            }
        }
        val evaluator = object : RelationshipEventEvaluator {
            override suspend fun evaluate(turn: RelationshipTurn): RelationshipEvaluation {
                evaluatorCalls += 1
                assertNotNull(transcript.findByTurnId(turn.sourceTurnId))
                assertContains(
                    assertNotNull(transcript.postCommitState(turn.sourceTurnId)).pendingStages,
                    RELATIONSHIP,
                )
                return DurableRelationshipEvaluator.evaluate(turn)
            }
        }
        val failingRelationship = object : RelationshipStateStore by relationship {
            private var failed = false

            override suspend fun append(event: RelationshipEvent) = if (!failed) {
                failed = true
                throw IllegalStateException("SQLITE_BUSY during relationship append")
            } else {
                relationship.append(event)
            }
        }
        val incarnationId: String
        try {
            val firstPipeline = relationshipRecoveryPipeline(
                transcript = transcript,
                incarnation = incarnation,
                memory = memory,
                llmClient = firstLlm,
                relationshipStore = failingRelationship,
                relationshipEventEvaluator = evaluator,
            )

            assertFailsWith<IllegalStateException> { firstPipeline.handle(request) }

            incarnationId = transcript.activeIncarnation().id
            val interrupted = assertNotNull(transcript.postCommitState(request.turnId))
            assertEquals(1, llmCalls)
            assertEquals(1, evaluatorCalls)
            assertEquals(1L, incarnation.read(incarnationId).evolutionIndex)
            assertEquals(emptySet(), interrupted.completedStages)
            assertNotNull(interrupted.plan.relationshipEvaluation)
            assertEquals(0, relationship.events(incarnationId, "CLI:user-1").size)
            assertEquals(0, memory.recent("CLI:local", 50).size)

        } finally {
            relationship.close()
            incarnation.shutdown()
            transcript.close()
        }

        val reopenedTranscript = SqlDelightTranscriptStore.open(dbPath)
        val reopenedIncarnation = SqlDelightIncarnationStateStore.open(
            dbPath = dbPath,
            ioDispatcher = newSqliteDispatcher("sql-relationship-restart-incarnation-test"),
            committedTranscriptStore = reopenedTranscript,
        )
        val reopenedRelationship = SqlDelightRelationshipStateStore.open(dbPath)
        val reopenedMemory = InMemoryMemoryPalace(DirectInferenceExecutor)
        try {
            val replay = relationshipRecoveryPipeline(
                transcript = reopenedTranscript,
                incarnation = reopenedIncarnation,
                memory = reopenedMemory,
                llmClient = ThrowingLlmClient,
                relationshipStore = reopenedRelationship,
                relationshipEventEvaluator = object : RelationshipEventEvaluator {
                    override suspend fun evaluate(turn: RelationshipTurn): RelationshipEvaluation =
                        error("persisted relationship evaluation must not be recomputed")
                },
            ).handle(request)
            val completed = assertNotNull(reopenedTranscript.postCommitState(request.turnId))

            assertEquals("response", replay.response)
            assertEquals(1L, reopenedIncarnation.read(incarnationId).evolutionIndex)
            assertEquals(1, reopenedRelationship.events(incarnationId, "CLI:user-1").size)
            assertEquals(1, reopenedMemory.recent("CLI:local", 50).size)
            assertEquals(completed.plan.requiredStages.toSet(), completed.completedStages)
        } finally {
            reopenedRelationship.close()
            reopenedIncarnation.shutdown()
            reopenedTranscript.close()
        }
    }

    private fun relationshipRecoveryPipeline(
        transcript: SqlDelightTranscriptStore,
        incarnation: SqlDelightIncarnationStateStore,
        memory: InMemoryMemoryPalace,
        llmClient: LlmClient,
        relationshipStore: RelationshipStateStore,
        relationshipEventEvaluator: RelationshipEventEvaluator,
    ) = DevelopmentMessagePipeline.create(
        personaConfig = testPersona(),
        llmClient = llmClient,
        store = MutableSessionStateStore(),
        incarnationStateStore = incarnation,
        transcriptStore = transcript,
        memoryStore = memory,
        relationshipStore = relationshipStore,
        relationshipEventEvaluator = relationshipEventEvaluator,
        centroidProvider = HomeostasisCentroidProvider { BioVector.Neutral },
        nowMs = { 1_000L },
    )

    private suspend fun openStores(): Stores {
        val transcript = SqlDelightTranscriptStore.open(dbPath)
        return Stores(
            transcript = transcript,
            session = SqlDelightSessionStateStore.open(dbPath),
            incarnation = SqlDelightIncarnationStateStore.open(
                dbPath = dbPath,
                ioDispatcher = newSqliteDispatcher("sql-pipeline-incarnation-test"),
                committedTranscriptStore = transcript,
            ),
            memory = SqlDelightMemoryRepository.open(dbPath),
            relationship = SqlDelightRelationshipStateStore.open(dbPath),
            diary = SqlDelightDiaryTaskStore.open(dbPath),
        )
    }

    private fun pipeline(
        stores: Stores,
        llmClient: LlmClient,
        diaryTaskStore: DiaryTaskStore = stores.diary,
        relationshipStore: RelationshipStateStore = stores.relationship,
        relationshipEventEvaluator: RelationshipEventEvaluator = DurableRelationshipEvaluator,
    ) = DevelopmentMessagePipeline.create(
        personaConfig = testPersona(),
        llmClient = llmClient,
        store = stores.session,
        incarnationStateStore = stores.incarnation,
        transcriptStore = stores.transcript,
        memoryStore = stores.memory,
        relationshipStore = relationshipStore,
        relationshipEventEvaluator = relationshipEventEvaluator,
        diaryTaskStore = diaryTaskStore,
        diaryTriggerCoordinator = DiaryTriggerCoordinator(
            taskStore = diaryTaskStore,
            checkpointStore = stores.diary,
            rawMemorySource = stores.memory,
        ),
        centroidProvider = HomeostasisCentroidProvider { BioVector.Neutral },
        nowMs = { 1_000L },
    )

    private fun testPersona() = PersonaConfig(
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

    private data class Stores(
        val transcript: SqlDelightTranscriptStore,
        val session: SqlDelightSessionStateStore,
        val incarnation: SqlDelightIncarnationStateStore,
        val memory: SqlDelightMemoryRepository,
        val relationship: SqlDelightRelationshipStateStore,
        val diary: SqlDelightDiaryTaskStore,
    ) {
        suspend fun close() {
            diary.close()
            relationship.close()
            memory.close()
            incarnation.shutdown()
            session.close()
            transcript.close()
        }
    }

    private object ValidLlmClient : LlmClient {
        override suspend fun complete(prompt: BuiltPrompt) = LlmOutput(
            internalLogic = "logic references HEURISTIC_FALLBACK",
            vectorDelta = mapOf(
                "L" to 0.3f,
                "P" to 0.0f,
                "E" to 0.0f,
                "S" to 0.0f,
                "tau" to 0.0f,
                "V" to 0.0f,
                "M" to 0.0f,
                "F" to 0.0f,
            ),
            response = "response",
        )
    }

    private object DurableRelationshipEvaluator : RelationshipEventEvaluator {
        override suspend fun evaluate(turn: RelationshipTurn) = RelationshipEvaluation(
            events = listOf(
                RelationshipEvent(
                    eventId = "${turn.sourceTurnId}:${RelationshipEventType.PREFERENCE_RESPECTED.name}",
                    incarnationId = turn.incarnationId,
                    canonicalSubjectId = turn.subjectId,
                    sourceTurnId = turn.sourceTurnId,
                    type = RelationshipEventType.PREFERENCE_RESPECTED,
                    confidence = 1.0f,
                    evidenceDigest = "durable SQL recovery",
                    createdAtMs = turn.completedAtMs,
                ),
            ),
            confidence = 1.0f,
        )
    }

    private object ThrowingLlmClient : LlmClient {
        override suspend fun complete(prompt: BuiltPrompt): LlmOutput =
            error("LLM must not run for a committed retry")
    }
}
