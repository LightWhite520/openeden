package io.openeden.runtime.pipeline

import io.openeden.runtime.affect.ShockState
import io.openeden.codebook.CodebookQuantizer
import io.openeden.codebook.QuantizationResult
import io.openeden.runtime.diary.SessionDiaryQueue
import io.openeden.runtime.diary.DiaryCheckpoint
import io.openeden.runtime.diary.DiaryCheckpointStore
import io.openeden.runtime.diary.DiaryRawMemoryCursor
import io.openeden.runtime.diary.DiaryRawMemorySource
import io.openeden.runtime.diary.DiaryTask
import io.openeden.runtime.diary.DiaryTaskStore
import io.openeden.runtime.diary.DiaryTriggerCoordinator
import io.openeden.runtime.inference.DirectInferenceExecutor
import io.openeden.runtime.inference.InferenceExecutor
import io.openeden.runtime.inference.RecordingInferenceExecutor
import io.openeden.runtime.session.MutableSessionStateStore
import io.openeden.runtime.session.SessionTurnGate
import io.openeden.runtime.state.StoredOriginCentroidProvider
import io.openeden.runtime.state.HomeostasisCentroidProvider
import io.openeden.runtime.state.VectorWriteService
import io.openeden.runtime.state.BackgroundDynamicsReducer


import io.openeden.bio.BioVector
import io.openeden.bio.VectorDelta
import io.openeden.memory.RetrievalMode
import io.openeden.persona.PersonaConfig
import io.openeden.persona.PersonaMode
import io.openeden.persona.PersonaSubState
import io.openeden.llm.LlmOutput
import io.openeden.llm.LlmCacheMetrics
import io.openeden.llm.LlmGenerationPolicyConfig
import io.openeden.llm.LlmGenerationSettings
import io.openeden.llm.LlmVerbosity
import io.openeden.llm.DevelopmentLlmStub
import io.openeden.memory.DeterministicMemoryEmbeddingModel
import io.openeden.memory.InMemoryMemoryPalace
import io.openeden.prompt.BuiltPrompt
import io.openeden.prompt.DefaultPromptBuilder
import io.openeden.prompt.PromptSegmentKind
import io.openeden.prompt.PromptSectionKeys
import io.openeden.relationship.HostIdentity
import io.openeden.relationship.DeterministicUserAffectAnalyzer
import io.openeden.relationship.InMemoryRelationshipStateStore
import io.openeden.relationship.RelationshipEvaluation
import io.openeden.relationship.RelationshipEvent
import io.openeden.relationship.RelationshipEventEvaluator
import io.openeden.relationship.RelationshipEventType
import io.openeden.relationship.RelationshipRoleResolver
import io.openeden.relationship.RelationshipStateStore
import io.openeden.relationship.RelationshipTurn
import io.openeden.relationship.UserAffectInfluenceMapper
import io.openeden.trace.TraceTag
import io.openeden.trace.TraceSpan
import io.openeden.trace.TraceStore
import io.openeden.runtime.affect.OmegaAccumulationConfig
import io.openeden.runtime.tick.SineWaveDimension
import io.openeden.runtime.tick.SineWaveFluctuationEngine
import io.openeden.runtime.tick.SineWaveFluctuationProfile
import io.openeden.transcript.InMemoryTranscriptStore
import io.openeden.llm.CacheMetricAvailability
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

class MessagePipelineTest {
    @Test
    fun `proposal phrase does not mutate relationship boundaries`() = runTest {
        val transcripts = InMemoryTranscriptStore("development")
        val relationshipStore = InMemoryRelationshipStateStore()
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = testPersonaConfig(),
            transcriptStore = transcripts,
            relationshipStore = relationshipStore,
        )

        pipeline.handle(testRequest().copy(text = "要不要吃饭"))

        val state = relationshipStore.readOrCreate("development", "QQ:u1")
        assertEquals(0.0f, state.boundarySensitivity)
        assertTrue(state.events.none { it.type == RelationshipEventType.BOUNDARY_REQUEST })
    }

    @Test
    fun `low confidence relationship evaluation does not mutate committed relationship state`() = runTest {
        val transcripts = InMemoryTranscriptStore("development")
        val relationshipStore = InMemoryRelationshipStateStore()
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = testPersonaConfig(),
            transcriptStore = transcripts,
            relationshipStore = relationshipStore,
            relationshipEventEvaluator = object : RelationshipEventEvaluator {
                override suspend fun evaluate(turn: RelationshipTurn): RelationshipEvaluation = RelationshipEvaluation(
                    events = listOf(
                        RelationshipEvent(
                            eventId = "${turn.sourceTurnId}:BOUNDARY_REQUEST",
                            incarnationId = turn.incarnationId,
                            canonicalSubjectId = turn.subjectId,
                            sourceTurnId = turn.sourceTurnId,
                            type = RelationshipEventType.BOUNDARY_REQUEST,
                            confidence = 0.74f,
                            evidenceDigest = "uncertain boundary request",
                            createdAtMs = turn.completedAtMs,
                        ),
                    ),
                    confidence = 0.74f,
                )
            },
        )

        pipeline.handle(testRequest())

        val state = relationshipStore.readOrCreate("development", "QQ:u1")
        assertEquals(0.0f, state.familiarity)
        assertTrue(state.events.isEmpty())
    }

    @Test
    fun `committed user turn appends evaluated relationship event with canonical turn context`() = runTest {
        val transcripts = InMemoryTranscriptStore("incarnation-a")
        val relationshipStore = InMemoryRelationshipStateStore()
        var evaluatedTurn: RelationshipTurn? = null
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = testPersonaConfig(),
            transcriptStore = transcripts,
            relationshipStore = relationshipStore,
            llmClient = object : io.openeden.llm.LlmClient {
                override suspend fun complete(prompt: BuiltPrompt): LlmOutput = LlmOutput(
                    internalLogic = "logic references HEURISTIC_FALLBACK",
                    vectorDelta = validDelta(),
                    response = "validated assistant response",
                )
            },
            relationshipEventEvaluator = object : RelationshipEventEvaluator {
                override suspend fun evaluate(turn: RelationshipTurn): RelationshipEvaluation {
                    evaluatedTurn = turn
                    return RelationshipEvaluation(
                        events = listOf(
                            RelationshipEvent(
                                eventId = "${turn.sourceTurnId}:BOUNDARY_REQUEST",
                                incarnationId = turn.incarnationId,
                                canonicalSubjectId = turn.subjectId,
                                sourceTurnId = turn.sourceTurnId,
                                type = RelationshipEventType.BOUNDARY_REQUEST,
                                confidence = 1.0f,
                                evidenceDigest = "validated boundary request",
                                createdAtMs = turn.completedAtMs,
                            ),
                        ),
                        confidence = 1.0f,
                    )
                }
            },
            nowMs = { 42L },
        )

        pipeline.handle(testRequest().copy(turnId = "relationship-turn", text = "不要这样，请停下"))

        val turn = assertNotNull(evaluatedTurn)
        assertEquals("relationship-turn", turn.sourceTurnId)
        assertEquals("incarnation-a", turn.incarnationId)
        assertEquals("QQ:u1", turn.subjectId)
        assertEquals("不要这样，请停下", turn.userText)
        assertEquals("validated assistant response", turn.assistantText)
        assertEquals(42L, turn.completedAtMs)
        assertEquals(
            listOf(RelationshipEventType.BOUNDARY_REQUEST),
            relationshipStore.events("incarnation-a", "QQ:u1").map(RelationshipEvent::type),
        )
    }

    @Test
    fun `repair and repeated consistency preserve distinct relationship state effects`() = runTest {
        val transcripts = InMemoryTranscriptStore("development")
        val relationshipStore = InMemoryRelationshipStateStore()
        relationshipStore.write(
            io.openeden.relationship.RelationshipState.neutral("development", "QQ:u1").copy(
                unresolvedTension = 0.2f,
            ),
        )
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = testPersonaConfig(),
            transcriptStore = transcripts,
            relationshipStore = relationshipStore,
        )

        pipeline.handle(testRequest().copy(turnId = "repair-turn", text = "对不起，我刚才弄错了"))
        val repaired = relationshipStore.readOrCreate("development", "QQ:u1")
        assertEquals(0.52f, repaired.trust)
        assertEquals(0.52f, repaired.safety)
        assertEquals(0.12f, repaired.unresolvedTension, absoluteTolerance = 0.0001f)

        pipeline.handle(testRequest().copy(turnId = "repeat-turn", text = "你一直都记得我们的约定"))
        val repeated = relationshipStore.readOrCreate("development", "QQ:u1")
        assertEquals(0.53f, repeated.trust)
        assertEquals(0.53f, repeated.safety)
        assertEquals(0.01f, repeated.familiarity)
        assertEquals(0.12f, repeated.unresolvedTension, absoluteTolerance = 0.0001f)
    }

    @Test
    fun `committed retry reuses one durable relationship evaluation after side effect cancellation`() = runTest {
        val transcripts = InMemoryTranscriptStore("development")
        val relationshipDelegate = InMemoryRelationshipStateStore()
        var appendCalls = 0
        val relationshipStore = object : RelationshipStateStore by relationshipDelegate {
            override suspend fun append(event: RelationshipEvent): io.openeden.relationship.RelationshipState {
                val state = relationshipDelegate.append(event)
                appendCalls += 1
                if (appendCalls == 1) throw CancellationException("cancel after relationship append")
                return state
            }
        }
        var calls = 0
        var llmCalls = 0
        val evaluator = object : RelationshipEventEvaluator {
            override suspend fun evaluate(turn: RelationshipTurn): RelationshipEvaluation {
                calls += 1
                val type = if (calls == 1) RelationshipEventType.REPAIR else RelationshipEventType.BOUNDARY_VIOLATION
                return RelationshipEvaluation(
                    events = listOf(
                        RelationshipEvent(
                            eventId = "${turn.sourceTurnId}:${type.name}",
                            incarnationId = turn.incarnationId,
                            canonicalSubjectId = turn.subjectId,
                            sourceTurnId = turn.sourceTurnId,
                            type = type,
                            confidence = 1.0f,
                            evidenceDigest = "evaluation-$calls",
                            createdAtMs = turn.completedAtMs,
                        ),
                    ),
                    confidence = 1.0f,
                )
            }
        }
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = testPersonaConfig(),
            transcriptStore = transcripts,
            relationshipStore = relationshipStore,
            relationshipEventEvaluator = evaluator,
            llmClient = object : io.openeden.llm.LlmClient {
                override suspend fun complete(prompt: BuiltPrompt): LlmOutput {
                    llmCalls += 1
                    return LlmOutput(
                        internalLogic = "retry test references HEURISTIC_FALLBACK",
                        vectorDelta = validDelta(),
                        response = "generated-response-$llmCalls",
                    )
                }
            },
        )
        val request = testRequest().copy(turnId = "cancellable-turn", text = "对不起，我刚才弄错了")

        kotlin.test.assertFailsWith<CancellationException> { pipeline.handle(request) }

        assertEquals(1, transcripts.page(50).turns.size)
        val retry = pipeline.handle(request)

        assertEquals(1, llmCalls)
        assertEquals("generated-response-1", retry.response)
        assertEquals(1, calls)
        assertEquals(
            listOf(RelationshipEventType.REPAIR),
            relationshipDelegate.events("development", "QQ:u1").map(RelationshipEvent::type),
        )
    }

    @Test
    fun `pipeline traces redacted manifest and unavailable provider cache metrics`() = runTest {
        val traces = io.openeden.trace.InMemoryTraceStore()
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = testPersonaConfig(),
            traceStore = traces,
            llmClient = object : io.openeden.llm.LlmClient {
                override suspend fun complete(prompt: BuiltPrompt): LlmOutput = LlmOutput(
                    internalLogic = "logic references NODE_12",
                    vectorDelta = validDelta(),
                    response = "response",
                )
            },
            quantizer = object : CodebookQuantizer {
                override suspend fun quantize(vector: BioVector, dissonance: Float): QuantizationResult =
                    QuantizationResult(listOf("NODE_12"), listOf("node definition"), 0.9f)
            },
        )

        val result = pipeline.handle(testRequest().copy(text = "user secret"))

        assertEquals(CacheMetricAvailability.UNOBSERVABLE, assertNotNull(result.cacheMetrics).availability)
        val manifestTrace = traces.snapshot().single { it.stage == "prompt_manifest" }
        assertEquals("4", manifestTrace.attributes["entry_count"])
        assertEquals(
            setOf(
                "entry_count",
                "system_contract_utf8_bytes",
                "system_contract_fingerprint",
                "persona_utf8_bytes",
                "persona_fingerprint",
                "incarnation_anchor_utf8_bytes",
                "incarnation_anchor_fingerprint",
                "history_utf8_bytes",
                "history_fingerprint",
            ),
            manifestTrace.attributes.keys,
        )
        assertFalse(traces.snapshot().toString().contains("user secret"))
    }

    @Test
    fun `ungrounded schema-valid output gets one repair attempt`() = runTest {
        var calls = 0
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = testPersonaConfig(),
            quantizer = object : CodebookQuantizer {
                override suspend fun quantize(vector: BioVector, dissonance: Float): QuantizationResult =
                    QuantizationResult(listOf("NODE_12"), listOf("node definition"), 0.9f)
            },
            llmClient = object : io.openeden.llm.LlmClient {
                override suspend fun complete(prompt: BuiltPrompt): LlmOutput {
                    calls += 1
                    return LlmOutput(
                        internalLogic = if (calls == 1) "logic" else "repair uses NODE_12",
                        vectorDelta = validDelta(),
                        response = "response",
                    )
                }
            },
        )

        val result = pipeline.handle(testRequest())

        assertEquals(2, calls)
        assertEquals(1, result.evolutionIndex)
        assertContains(result.traceTags, TraceTag.LlmGroundingRegenerated)
        assertContains(result.traceTags, TraceTag.LlmGroundingRepaired)
    }

    @Test
    fun `pipeline exposes aggregated cache metrics and traces weighted hit rate`() = runTest {
        val traces = io.openeden.trace.InMemoryTraceStore()
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = testPersonaConfig(),
            traceStore = traces,
            llmClient = object : io.openeden.llm.LlmClient {
                override suspend fun complete(prompt: BuiltPrompt): LlmOutput = LlmOutput(
                    internalLogic = "logic references NODE_12",
                    vectorDelta = validDelta(),
                    response = "response",
                    cacheMetrics = LlmCacheMetrics(9_000, 6_500),
                )
            },
            quantizer = object : CodebookQuantizer {
                override suspend fun quantize(vector: BioVector, dissonance: Float): QuantizationResult =
                    QuantizationResult(listOf("NODE_12"), listOf("node definition"), 0.9f)
            },
        )

        val result = pipeline.handle(testRequest())

        val metrics = assertNotNull(result.cacheMetrics)
        assertEquals(6_500.0 / 9_000.0, metrics.cacheHitRate, 0.000001)
        val cacheTrace = traces.snapshot().single { it.stage == "llm_cache" }
        assertEquals("6500", cacheTrace.attributes["cached_input_count"])
        assertEquals("0.7222222222222222", cacheTrace.attributes["cache_hit_rate"])
    }

    @Test
    fun `ungrounded repair is rejected without state evolution`() = runTest {
        var calls = 0
        val store = MutableSessionStateStore()
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = testPersonaConfig(),
            store = store,
            quantizer = object : CodebookQuantizer {
                override suspend fun quantize(vector: BioVector, dissonance: Float): QuantizationResult =
                    QuantizationResult(listOf("NODE_12"), listOf("node definition"), 0.9f)
            },
            llmClient = object : io.openeden.llm.LlmClient {
                override suspend fun complete(prompt: BuiltPrompt): LlmOutput {
                    calls += 1
                    return LlmOutput("logic", validDelta(), "response")
                }
            },
        )

        val result = pipeline.handle(testRequest())

        assertEquals(2, calls)
        assertEquals(0, store.read("QQ:100").evolutionIndex)
        assertEquals(null, result.response)
        assertContains(result.traceTags, TraceTag.LlmGroundingRejected)
    }

    @Test
    fun `runs one development message turn`() = runTest {
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = testPersonaConfig(),
            nowMs = { 1_787_414_712_000L },
        )

        val result = pipeline.handle(
            DevelopmentMessageRequest(
                turnId = "turn-1",
                platform = "QQ",
                scopeId = "100",
                userId = "u1",
                text = "hello",
                emotionConfidence = 0.49f,
                emotionDelta = VectorDelta(p = -1.0f),
            ),
        )

        assertEquals("QQ:100", result.sessionId)
        assertEquals(RetrievalMode.CONGRUENT, result.retrievalMode)
        assertContains(result.traceTags, TraceTag.CodebookHeuristicFallback)
        assertEquals(1, result.evolutionIndex)
        assertEquals(BioVector.Neutral, result.updatedVector)
        assertContains(result.promptPreview, "\"bio_core_state\"")
        val temporal = result.prompt.segments.single { it.kind == PromptSegmentKind.TEMPORAL }.text
        assertContains(temporal, "\"temporal_context\"")
        assertFalse(temporal.contains("\"exact_time\""))
        assertEquals("not_triggered", result.diaryOutcome)
    }

    @Test
    fun `user turn traces proposed effective and homeostatic vector reduction`() = runTest {
        val traces = io.openeden.trace.InMemoryTraceStore()
        val sessions = MutableSessionStateStore()
        val incarnations = io.openeden.runtime.incarnation.MutableIncarnationStateStore(
            transcriptStore = sessions.transcript,
        )
        val initial = incarnations.readOrCreate(
            incarnationId = "development",
            personaMode = PersonaMode.GROWTH,
            personaStartSubState = PersonaSubState.PRE_COMMAND,
        )
        incarnations.write(initial.copy(vector = initial.vector.copy(p = 0.98f)))
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = testPersonaConfig(),
            store = sessions,
            incarnationStateStore = incarnations,
            traceStore = traces,
            llmClient = object : io.openeden.llm.LlmClient {
                override suspend fun complete(prompt: BuiltPrompt): LlmOutput = LlmOutput(
                    internalLogic = "ordinary turn references HEURISTIC_FALLBACK",
                    vectorDelta = validDelta().toMutableMap().apply { put("P", 0.1f) },
                    response = "response",
                )
            },
        )

        val result = pipeline.handle(testRequest().copy(emotionConfidence = 0.49f))

        assertTrue(result.updatedVector.p in 0.98f..<0.99f)
        val commitTrace = traces.snapshot().single { it.stage == "state_commit" }
        assertTrue("vector_delta_proposed" in commitTrace.attributes)
        assertTrue("vector_delta_effective" in commitTrace.attributes)
        assertTrue("vector_delta_homeostatic" in commitTrace.attributes)
        assertTrue("vector_result" in commitTrace.attributes)
        assertTrue("vector_reasons" in commitTrace.attributes)
    }

    @Test
    fun `nonfinite model delta rejects the public turn without state or memory effects`() = runTest {
        val traces = io.openeden.trace.InMemoryTraceStore()
        val sessions = MutableSessionStateStore()
        val memories = InMemoryMemoryPalace(DirectInferenceExecutor)
        val incarnations = io.openeden.runtime.incarnation.MutableIncarnationStateStore(
            transcriptStore = sessions.transcript,
        )
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = testPersonaConfig(),
            store = sessions,
            incarnationStateStore = incarnations,
            traceStore = traces,
            memoryStore = memories,
            llmClient = object : io.openeden.llm.LlmClient {
                override suspend fun complete(prompt: BuiltPrompt): LlmOutput = LlmOutput(
                    internalLogic = "invalid delta turn references HEURISTIC_FALLBACK",
                    vectorDelta = validDelta().toMutableMap().apply { put("P", Float.NaN) },
                    response = "response",
                )
            },
        )

        val result = pipeline.handle(testRequest())

        assertEquals(null, result.response)
        assertEquals(0L, result.evolutionIndex)
        assertEquals(BioVector.Neutral, result.updatedVector)
        assertTrue(result.updatedVector.toList().all(Float::isFinite))
        assertTrue(memories.recent("QQ:100", 1).isEmpty())
        assertTrue(result.validationErrors.isNotEmpty())
        assertTrue(
            traces.snapshot()
                .single { it.stage == "state_commit" }
                .tags
                .none { it == io.openeden.trace.TraceTag.VectorWriteSerialized },
        )
    }

    @Test
    fun `nonfinite P or F threshold combinations cannot trigger shock or omega`() = runTest {
        val cases = listOf(
            "nan-p" to validDelta().toMutableMap().apply {
                put("P", Float.NaN)
                put("F", 0.4f)
            },
            "infinite-f" to validDelta().toMutableMap().apply {
                put("P", -0.5f)
                put("F", Float.POSITIVE_INFINITY)
            },
        )

        for ((caseName, modelDelta) in cases) {
            val sessions = MutableSessionStateStore()
            val memories = InMemoryMemoryPalace(DirectInferenceExecutor)
            val incarnations = io.openeden.runtime.incarnation.MutableIncarnationStateStore(
                transcriptStore = sessions.transcript,
            )
            val pipeline = DevelopmentMessagePipeline.create(
                personaConfig = testPersonaConfig(),
                store = sessions,
                incarnationStateStore = incarnations,
                memoryStore = memories,
                llmClient = object : io.openeden.llm.LlmClient {
                    override suspend fun complete(prompt: BuiltPrompt): LlmOutput = LlmOutput(
                        internalLogic = "invalid shock turn references HEURISTIC_FALLBACK",
                        vectorDelta = modelDelta,
                        response = "response",
                    )
                },
            )

            val result = pipeline.handle(
                testRequest().copy(turnId = "invalid-shock-$caseName", emotionConfidence = 0.9f),
            )
            val state = incarnations.read("development")

            assertEquals(null, result.response)
            assertEquals(0L, result.evolutionIndex)
            assertEquals(null, state.shockState, caseName)
            assertEquals(0.0f, state.omega.value, caseName)
            assertTrue(state.vector.toList().all(Float::isFinite), caseName)
            assertTrue(memories.recent("QQ:100", 1).isEmpty(), caseName)
        }
    }

    @Test
    fun `nonfinite external confidence cannot authorize shock`() = runTest {
        val sessions = MutableSessionStateStore()
        val incarnations = io.openeden.runtime.incarnation.MutableIncarnationStateStore(
            transcriptStore = sessions.transcript,
        )
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = testPersonaConfig(),
            store = sessions,
            incarnationStateStore = incarnations,
            llmClient = object : io.openeden.llm.LlmClient {
                override suspend fun complete(prompt: BuiltPrompt): LlmOutput = LlmOutput(
                    internalLogic = "invalid confidence turn references HEURISTIC_FALLBACK",
                    vectorDelta = validDelta().toMutableMap().apply {
                        put("P", -0.5f)
                        put("F", 0.4f)
                    },
                    response = "response",
                )
            },
        )

        val result = pipeline.handle(
            testRequest().copy(turnId = "invalid-shock-confidence", emotionConfidence = Float.POSITIVE_INFINITY),
        )
        val state = incarnations.read("development")

        assertEquals(null, result.response)
        assertEquals(0L, result.evolutionIndex)
        assertEquals(null, state.shockState)
        assertEquals(0.0f, state.omega.value)
        assertTrue(state.vector.toList().all(Float::isFinite))
    }

    @Test
    fun `invalid high confidence delta cannot plan relationship or memory side effects`() = runTest {
        val sessions = MutableSessionStateStore()
        val memories = InMemoryMemoryPalace(DirectInferenceExecutor)
        val relationships = InMemoryRelationshipStateStore()
        var relationshipEvaluations = 0
        val incarnations = io.openeden.runtime.incarnation.MutableIncarnationStateStore(
            transcriptStore = sessions.transcript,
        )
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = testPersonaConfig(),
            store = sessions,
            incarnationStateStore = incarnations,
            memoryStore = memories,
            relationshipStore = relationships,
            relationshipEventEvaluator = object : RelationshipEventEvaluator {
                override suspend fun evaluate(turn: RelationshipTurn): RelationshipEvaluation {
                    relationshipEvaluations += 1
                    return RelationshipEvaluation(emptyList(), confidence = 1.0f)
                }
            },
            llmClient = object : io.openeden.llm.LlmClient {
                override suspend fun complete(prompt: BuiltPrompt): LlmOutput = LlmOutput(
                    internalLogic = "invalid authoritative turn references HEURISTIC_FALLBACK",
                    vectorDelta = validDelta().toMutableMap().apply {
                        put("P", Float.NEGATIVE_INFINITY)
                        put("F", 0.4f)
                    },
                    response = "must not be delivered",
                )
            },
        )

        val result = pipeline.handle(
            testRequest().copy(turnId = "invalid-authoritative", emotionConfidence = 0.9f),
        )
        val state = incarnations.read("development")

        assertEquals(null, result.response)
        assertEquals(0L, state.evolutionIndex)
        assertEquals(BioVector.Neutral, state.vector)
        assertEquals(null, state.shockState)
        assertEquals(0.0f, state.omega.value)
        assertEquals(0, relationshipEvaluations)
        assertTrue(relationships.events("development", "QQ:u1").isEmpty())
        assertTrue(memories.recent("QQ:100", 10).isEmpty())
    }

    @Test
    fun `dead zone committed zero suppresses diary and remains stable memory`() = runTest {
        val sessions = MutableSessionStateStore()
        val memories = InMemoryMemoryPalace(DirectInferenceExecutor)
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = testPersonaConfig(),
            store = sessions,
            memoryStore = memories,
            llmClient = object : io.openeden.llm.LlmClient {
                override suspend fun complete(prompt: BuiltPrompt): LlmOutput = LlmOutput(
                    internalLogic = "dead zone turn references HEURISTIC_FALLBACK",
                    vectorDelta = validDelta().toMutableMap().apply { put("P", 0.004f) },
                    response = "response",
                )
            },
        )

        val result = pipeline.handle(testRequest().copy(turnId = "dead-zone-memory"))
        val memory = memories.recent("QQ:100", 1).single()

        assertEquals(VectorDelta.Zero, memory.metadata.deltaVec)
        assertEquals("not_triggered", result.diaryOutcome)
        assertEquals(1, memories.stableVectors("development", 10).size)
    }

    @Test
    fun `homeostatic committed change drives diary and stable decisions`() = runTest {
        val sessions = MutableSessionStateStore()
        val memories = InMemoryMemoryPalace(DirectInferenceExecutor)
        val incarnations = io.openeden.runtime.incarnation.MutableIncarnationStateStore(
            transcriptStore = sessions.transcript,
        )
        val initial = incarnations.readOrCreate(
            incarnationId = "development",
            personaMode = PersonaMode.GROWTH,
            personaStartSubState = PersonaSubState.PRE_COMMAND,
        ).copy(
            vector = BioVector.Neutral.copy(p = 0.8f),
            origin = BioVector.Neutral.copy(p = 0.3f),
            lastRuntimeTickAtMs = 0L,
            lastVectorDynamicsAtMs = 0L,
        )
        incarnations.write(initial)
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = testPersonaConfig(),
            store = sessions,
            incarnationStateStore = incarnations,
            memoryStore = memories,
            centroidProvider = HomeostasisCentroidProvider { initial.origin },
            nowMs = { 3_600_000L },
            llmClient = object : io.openeden.llm.LlmClient {
                override suspend fun complete(prompt: BuiltPrompt): LlmOutput = LlmOutput(
                    internalLogic = "homeostasis memory turn references HEURISTIC_FALLBACK",
                    vectorDelta = validDelta(),
                    response = "response",
                )
            },
        )

        val result = pipeline.handle(testRequest().copy(turnId = "homeostasis-memory"))
        val memory = memories.recent("QQ:100", 1).single()

        assertTrue(memory.metadata.deltaVec.p < -0.05f)
        assertEquals("enqueued", result.diaryOutcome)
        assertTrue(memories.stableVectors("development", 10).isEmpty())
    }

    @Test
    fun `pipeline applies elapsed catch up before bounded homeostasis`() = runTest {
        val sessions = MutableSessionStateStore()
        val incarnations = io.openeden.runtime.incarnation.MutableIncarnationStateStore(
            transcriptStore = sessions.transcript,
        )
        val initial = incarnations.readOrCreate(
            incarnationId = "development",
            personaMode = PersonaMode.GROWTH,
            personaStartSubState = PersonaSubState.PRE_COMMAND,
        ).copy(
            vector = BioVector.Neutral.copy(p = 0.8f),
            origin = BioVector.Neutral.copy(p = 0.3f),
            lastRuntimeTickAtMs = 0L,
        )
        incarnations.write(initial)
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = testPersonaConfig(),
            store = sessions,
            incarnationStateStore = incarnations,
            centroidProvider = HomeostasisCentroidProvider { initial.origin },
            nowMs = { 3_600_000L },
            llmClient = object : io.openeden.llm.LlmClient {
                override suspend fun complete(prompt: BuiltPrompt): LlmOutput = LlmOutput(
                    internalLogic = "elapsed turn references HEURISTIC_FALLBACK",
                    vectorDelta = validDelta(),
                    response = "response",
                )
            },
        )

        val result = pipeline.handle(testRequest())

        assertTrue(result.updatedVector.p in 0.3f..<0.8f)
        assertTrue(TraceTag.BackgroundDrift in result.traceTags)
    }

    @Test
    fun `multiple turns cannot consume the same homeostasis interval twice`() = runTest {
        val sessions = MutableSessionStateStore()
        val incarnations = io.openeden.runtime.incarnation.MutableIncarnationStateStore(
            transcriptStore = sessions.transcript,
        )
        val initial = incarnations.readOrCreate(
            incarnationId = "development",
            personaMode = PersonaMode.GROWTH,
            personaStartSubState = PersonaSubState.PRE_COMMAND,
        ).copy(
            vector = BioVector.Neutral.copy(p = 0.8f),
            origin = BioVector.Neutral.copy(p = 0.3f),
            lastRuntimeTickAtMs = 0L,
        )
        incarnations.write(initial)
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = testPersonaConfig(),
            store = sessions,
            incarnationStateStore = incarnations,
            centroidProvider = HomeostasisCentroidProvider { initial.origin },
            nowMs = { 3_600_000L },
            llmClient = object : io.openeden.llm.LlmClient {
                override suspend fun complete(prompt: BuiltPrompt): LlmOutput = LlmOutput(
                    internalLogic = "elapsed turn references HEURISTIC_FALLBACK",
                    vectorDelta = validDelta(),
                    response = "response",
                )
            },
        )

        val first = pipeline.handle(testRequest().copy(turnId = "homeostasis-once-1"))
        val second = pipeline.handle(testRequest().copy(turnId = "homeostasis-once-2"))

        assertTrue(first.updatedVector.p in 0.3f..<0.8f)
        assertEquals(first.updatedVector.p, second.updatedVector.p, absoluteTolerance = 0.000001f)
    }

    @Test
    fun `memory committed delta includes background catch up before reduction`() = runTest {
        val sessions = MutableSessionStateStore()
        val memories = InMemoryMemoryPalace(DirectInferenceExecutor)
        val incarnations = io.openeden.runtime.incarnation.MutableIncarnationStateStore(
            transcriptStore = sessions.transcript,
        )
        val fluctuation = SineWaveFluctuationEngine(
            SineWaveFluctuationProfile(
                dimensions = List(8) {
                    SineWaveDimension(amplitude = 0.04f, frequencyHz = 0.002f, phaseRadians = 0.1f)
                },
            ),
        )
        val expectedDelta = fluctuation.deltaBetween(0L, 1_000L)
        val initial = incarnations.readOrCreate(
            incarnationId = "development",
            personaMode = PersonaMode.GROWTH,
            personaStartSubState = PersonaSubState.PRE_COMMAND,
        ).copy(
            origin = BioVector.Neutral.apply(expectedDelta),
            lastRuntimeTickAtMs = 0L,
            lastVectorDynamicsAtMs = 0L,
        )
        incarnations.write(initial)
        val writer = VectorWriteService(
            incarnationStore = incarnations,
            backgroundDynamicsReducer = BackgroundDynamicsReducer(
                fluctuation = fluctuation,
                omegaConfig = OmegaAccumulationConfig(sWearRate = 0.0f, dissonanceWearRate = 0.0f),
                startedAtMs = 0L,
            ),
        )
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = testPersonaConfig(),
            store = sessions,
            incarnationStateStore = incarnations,
            vectorWriteService = writer,
            memoryStore = memories,
            centroidProvider = HomeostasisCentroidProvider { initial.origin },
            nowMs = { 1_000L },
            llmClient = object : io.openeden.llm.LlmClient {
                override suspend fun complete(prompt: BuiltPrompt): LlmOutput = LlmOutput(
                    internalLogic = "background momentum references HEURISTIC_FALLBACK",
                    vectorDelta = validDelta(),
                    response = "response",
                )
            },
        )

        pipeline.handle(testRequest().copy(turnId = "background-momentum"))

        val committed = memories.recent("QQ:100", 1).single().metadata.deltaVec.toList()
        expectedDelta.toList().zip(committed).forEach { (expected, actual) ->
            assertEquals(expected, actual, absoluteTolerance = 0.000001f)
        }
    }

    @Test
    fun `enqueues diary event when llm delta changes vector`() = runTest {
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = testPersonaConfig(),
            llmClient = object : io.openeden.llm.LlmClient {
                override suspend fun complete(prompt: BuiltPrompt): LlmOutput = LlmOutput(
                    internalLogic = "logic references HEURISTIC_FALLBACK",
                    vectorDelta = mapOf(
                        "L" to 0.0f,
                        "P" to 0.2f,
                        "E" to 0.0f,
                        "S" to 0.0f,
                        "tau" to 0.0f,
                        "V" to 0.0f,
                        "M" to 0.0f,
                        "F" to 0.0f,
                    ),
                    response = "response",
                )
            },
        )

        val result = pipeline.handle(
            DevelopmentMessageRequest(
                turnId = "turn-2",
                platform = "QQ",
                scopeId = "100",
                userId = "u1",
                text = "hello",
                emotionConfidence = 0.49f,
            ),
        )

        assertEquals("enqueued", result.diaryOutcome)
        assertTrue(result.updatedVector.p in 0.5f..<0.7f)
    }

    @Test
    fun `below-threshold durable diary trigger is reported as skipped rather than enqueued`() = runTest {
        val coordinator = DiaryTriggerCoordinator(
            taskStore = NoopDiaryTaskStore,
            checkpointStore = NoopDiaryCheckpointStore,
            rawMemorySource = NoopDiaryRawMemorySource,
        )
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = testPersonaConfig(),
            llmClient = object : io.openeden.llm.LlmClient {
                override suspend fun complete(prompt: BuiltPrompt): LlmOutput = LlmOutput(
                    internalLogic = "logic references HEURISTIC_FALLBACK",
                    vectorDelta = mapOf(
                        "L" to 0.0f,
                        "P" to 0.2f,
                        "E" to 0.0f,
                        "S" to 0.0f,
                        "tau" to 0.0f,
                        "V" to 0.0f,
                        "M" to 0.0f,
                        "F" to 0.0f,
                    ),
                    response = "response",
                )
            },
            diaryTriggerCoordinator = coordinator,
        )

        val result = pipeline.handle(testRequest().copy(turnId = "below-threshold-diary"))

        assertEquals("skipped_below_threshold", result.diaryOutcome)
    }

    @Test
    fun `shock back-detection persists shock state and omega jump behind confidence gate`() = runTest {
        val store = MutableSessionStateStore()
        val incarnationStore = io.openeden.runtime.incarnation.MutableIncarnationStateStore(
            transcriptStore = store.transcript,
        )
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = testPersonaConfig(),
            store = store,
            incarnationStateStore = incarnationStore,
            llmClient = object : io.openeden.llm.LlmClient {
                override suspend fun complete(prompt: BuiltPrompt): LlmOutput = LlmOutput(
                    internalLogic = "a severe discontinuity was inferred from HEURISTIC_FALLBACK",
                    vectorDelta = mapOf(
                        "L" to 0.0f,
                        "P" to -0.5f,
                        "E" to 0.0f,
                        "S" to 0.0f,
                        "tau" to 0.0f,
                        "V" to 0.0f,
                        "M" to 0.0f,
                        "F" to 0.4f,
                    ),
                    response = "response",
                )
            },
        )

        pipeline.handle(
            DevelopmentMessageRequest(
                turnId = "turn-3",
                platform = "QQ",
                scopeId = "100",
                userId = "u1",
                text = "hello",
                emotionConfidence = 0.65f,
            ),
        )

        val state = incarnationStore.read("development")
        val shock = assertNotNull(state.shockState)
        assertEquals(true, shock.active)
        assertEquals(0.4f, shock.intensity)
        assertEquals(0.06f, state.omega.value, 0.0001f)
    }

    @Test
    fun `repeated shock detections merge without repeating activation omega jump`() = runTest {
        val store = MutableSessionStateStore()
        val incarnationStore = io.openeden.runtime.incarnation.MutableIncarnationStateStore(
            transcriptStore = store.transcript,
        )
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = testPersonaConfig(),
            store = store,
            incarnationStateStore = incarnationStore,
            llmClient = object : io.openeden.llm.LlmClient {
                override suspend fun complete(prompt: BuiltPrompt): LlmOutput = LlmOutput(
                    internalLogic = "a severe discontinuity was inferred from HEURISTIC_FALLBACK",
                    vectorDelta = mapOf(
                        "L" to 0.0f,
                        "P" to -0.5f,
                        "E" to 0.0f,
                        "S" to 0.0f,
                        "tau" to 0.0f,
                        "V" to 0.0f,
                        "M" to 0.0f,
                        "F" to 0.4f,
                    ),
                    response = "response",
                )
            },
        )

        pipeline.handle(testRequest().copy(turnId = "shock-1", emotionConfidence = 0.65f))
        pipeline.handle(testRequest().copy(turnId = "shock-2", emotionConfidence = 0.65f))

        val state = incarnationStore.read("development")
        assertEquals(0.64f, assertNotNull(state.shockState).intensity, absoluteTolerance = 0.0001f)
        assertEquals(0.06f, state.omega.value, absoluteTolerance = 0.0001f)
        assertEquals(2, state.evolutionIndex)
    }

    @Test
    fun `pipeline runs runtime math through inference executor`() = runTest {
        val executor = RecordingInferenceExecutor()
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = testPersonaConfig(),
            inferenceExecutor = executor,
        )

        pipeline.handle(
            DevelopmentMessageRequest(
                turnId = "turn-4",
                platform = "QQ",
                scopeId = "100",
                userId = "u1",
                text = "hello",
                emotionConfidence = 0.49f,
            ),
        )

        assertTrue(executor.calls >= 4)
    }

    @Test
    fun `explicit affect signal uses its confidence for kernel trace`() = runTest {
        val traces = io.openeden.trace.InMemoryTraceStore()
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = testPersonaConfig(),
            traceStore = traces,
        )

        pipeline.handle(
            DevelopmentMessageRequest(
                turnId = "turn-5",
                platform = "QQ",
                scopeId = "100",
                userId = "u1",
                text = "hello",
                emotionConfidence = 0.8f,
                emotionDelta = VectorDelta(p = -0.1f),
            ),
        )

        val affectSpan = traces.snapshot().single { it.stage == "user_affect_inference" }
        assertContains(affectSpan.tags, TraceTag.UserAffectInferred)
        assertTrue(TraceTag.UserAffectFallback !in affectSpan.tags)
    }

    @Test
    fun `existing session keeps its selected persona starting point`() = runTest {
        val store = MutableSessionStateStore()
        val incarnationStore = io.openeden.runtime.incarnation.MutableIncarnationStateStore(
            transcriptStore = store.transcript,
        )
        DevelopmentMessagePipeline.create(
            personaConfig = testPersonaConfig(PersonaSubState.TRUE_SELF),
            store = store,
            incarnationStateStore = incarnationStore,
        ).handle(testRequest())

        val result = DevelopmentMessagePipeline.create(
            personaConfig = testPersonaConfig(PersonaSubState.AWAKENED, PersonaMode.LEGACY),
            store = store,
            incarnationStateStore = incarnationStore,
        ).handle(testRequest())

        assertContains(result.promptPreview, "TRUE_SELF")
        assertContains(result.promptPreview, "\"persona_mode\": \"GROWTH\"")
        assertTrue("AWAKENED\"" !in result.promptPreview)
    }

    @Test
    fun `pipeline grants host role and address only to exact configured sender`() = runTest {
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = testPersonaConfig(),
            relationshipRoleResolver = RelationshipRoleResolver(
                host = HostIdentity("QQ", "owner"),
                hostAddress = "Captain",
            ),
        )

        val host = pipeline.handle(testRequest(userId = "owner"))
        val member = pipeline.handle(testRequest(userId = "member"))
        val heartbeat = pipeline.handle(
            testRequest(userId = "INTERNAL").copy(source = TurnSource.HEARTBEAT),
        )

        assertContains(host.promptPreview, "\"relationship_role\": \"HOST\"")
        assertContains(host.promptPreview, "\"relationship_address\": \"Captain\"")
        assertContains(member.promptPreview, "\"relationship_role\": \"INTERLOCUTOR\"")
        assertContains(member.promptPreview, "\"relationship_address\": null")
        assertContains(heartbeat.promptPreview, "\"relationship_role\": \"INTERLOCUTOR\"")
        assertContains(heartbeat.promptPreview, "\"relationship_address\": null")
    }

    @Test
    fun `applies generation policy from pre ticked internal vector and traces safe settings`() = runTest {
        val store = MutableSessionStateStore()
        store.write(
            io.openeden.runtime.session.SessionStateStore.neutral("QQ:100"),
        )
        val traces = io.openeden.trace.InMemoryTraceStore()
        var capturedSettings: LlmGenerationSettings? = null
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = testPersonaConfig(),
            store = store,
            traceStore = traces,
            llmGenerationPolicyConfig = LlmGenerationPolicyConfig(
                temperatureMin = 0.2f,
                temperatureMax = 1.0f,
            ),
            llmClient = object : io.openeden.llm.LlmClient {
                override suspend fun complete(prompt: BuiltPrompt): LlmOutput = error("settings overload expected")

                override suspend fun complete(
                    prompt: BuiltPrompt,
                    generationSettings: LlmGenerationSettings,
                ): LlmOutput {
                    capturedSettings = generationSettings
                    return LlmOutput(
                        "logic",
                        mapOf(
                            "L" to 0.0f,
                            "P" to 0.0f,
                            "E" to 0.0f,
                            "S" to 0.0f,
                            "tau" to 0.0f,
                            "V" to 0.0f,
                            "M" to 0.0f,
                            "F" to 0.0f,
                        ),
                        "response",
                    )
                }
            },
        )

        pipeline.handle(
            testRequest().copy(
                emotionConfidence = 1.0f,
                emotionDelta = VectorDelta(l = -0.25f, s = 0.25f, v = -0.25f),
            ),
        )

        assertEquals(0.8f, capturedSettings?.temperature)
        assertEquals(LlmVerbosity.LOW, capturedSettings?.verbosity)
        assertEquals(false, traces.snapshot().single { it.stage == "pre_tick" }.attributes["skipped"]?.toBoolean())
        val policyTrace = traces.snapshot().single { it.stage == "llm_generation_policy" }
        assertEquals("-0.5", policyTrace.attributes["internal_l"])
        assertEquals("0.5", policyTrace.attributes["internal_s"])
        assertEquals("-0.5", policyTrace.attributes["internal_v"])
        assertEquals("0.8", policyTrace.attributes["temperature"])
        assertEquals("low", policyTrace.attributes["verbosity"])
        assertTrue("max_output_tokens" !in policyTrace.attributes)
        assertEquals(
            setOf("internal_l", "internal_s", "internal_v", "temperature", "verbosity"),
            policyTrace.attributes.keys,
        )
        val traceAttributes = policyTrace.attributes.entries.flatMap { entry ->
            listOf(entry.key, entry.value).map(String::lowercase)
        }
        val sensitiveTerms = listOf("prompt", "persona", "system", "key", "api", "internal logic", "raw memory")
        assertTrue(sensitiveTerms.none { term -> traceAttributes.any { it.contains(term) } })
    }

    @Test
    fun `direct construction uses default generation policy config`() {
        val store = MutableSessionStateStore()
        val vectorWriter = VectorWriteService(store)
        val memoryStore = InMemoryMemoryPalace(
            inferenceExecutor = DirectInferenceExecutor,
            embeddingModel = DeterministicMemoryEmbeddingModel,
        )

        val pipeline = DevelopmentMessagePipeline(
            testPersonaConfig(),
            store,
            io.openeden.codebook.HeuristicCodebookFallback(),
            memoryStore,
            DefaultPromptBuilder(),
            DevelopmentLlmStub(),
            vectorWriter,
            SessionDiaryQueue(),
            DirectInferenceExecutor,
            memoryStore,
            DeterministicMemoryEmbeddingModel,
            StoredOriginCentroidProvider(store),
            SessionTurnGate(vectorWriter.mutexRegistry),
            null,
            null,
            null,
            DeterministicUserAffectAnalyzer(),
            InMemoryRelationshipStateStore(),
            RelationshipRoleResolver(host = null),
            UserAffectInfluenceMapper.Default,
            null,
        )

        assertNotNull(pipeline)
    }

    @Test
    fun `legacy positional create call remains source compatible`() {
        val pipeline = DevelopmentMessagePipeline.create(
            testPersonaConfig(),
            DevelopmentLlmStub(),
            MutableSessionStateStore(),
        )

        assertNotNull(pipeline)
    }

    @Test
    fun `llm generation policy trace append runs inside inference executor`() = runTest {
        val executor = BoundaryRecordingInferenceExecutor()
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = testPersonaConfig(),
            inferenceExecutor = executor,
            traceStore = object : TraceStore {
                override suspend fun append(span: TraceSpan) {
                    if (span.stage == "llm_generation_policy") {
                        executor.policyTraceAppendedInsideRun = executor.inferenceRunning
                    }
                }
            },
        )

        pipeline.handle(testRequest())

        assertEquals(true, executor.policyTraceAppendedInsideRun)
    }

    @Test
    fun `prompt manifest trace append runs inside inference executor`() = runTest {
        val executor = BoundaryRecordingInferenceExecutor()
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = testPersonaConfig(),
            inferenceExecutor = executor,
            traceStore = object : TraceStore {
                override suspend fun append(span: TraceSpan) {
                    if (span.stage == "prompt_manifest") {
                        executor.promptManifestTraceAppendedInsideRun = executor.inferenceRunning
                    }
                }
            },
        )

        pipeline.handle(testRequest())

        assertEquals(true, executor.promptManifestTraceAppendedInsideRun)
    }

    @Test
    fun `pre tick trace append runs inside inference executor`() = runTest {
        val executor = BoundaryRecordingInferenceExecutor()
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = testPersonaConfig(),
            inferenceExecutor = executor,
            traceStore = object : TraceStore {
                override suspend fun append(span: TraceSpan) {
                    if (span.stage == "pre_tick") {
                        executor.preTickTraceAppendedInsideRun = executor.inferenceRunning
                    }
                }
            },
        )

        pipeline.handle(
            testRequest().copy(
                emotionConfidence = 0.8f,
                emotionDelta = VectorDelta(p = -0.1f),
            ),
        )

        assertEquals(true, executor.preTickTraceAppendedInsideRun)
    }

    @Test
    fun `user affect mapping trace append runs inside inference executor`() = runTest {
        val executor = BoundaryRecordingInferenceExecutor()
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = testPersonaConfig(),
            inferenceExecutor = executor,
            traceStore = object : TraceStore {
                override suspend fun append(span: TraceSpan) {
                    if (span.stage == "user_affect_mapping") {
                        executor.affectMappingTraceAppendedInsideRun = executor.inferenceRunning
                    }
                }
            },
        )

        pipeline.handle(
            testRequest().copy(
                emotionConfidence = 0.0f,
                emotionDelta = VectorDelta.Zero,
            ),
        )

        assertEquals(true, executor.affectMappingTraceAppendedInsideRun)
    }

    private fun testPersonaConfig(
        startSubState: PersonaSubState = PersonaSubState.PRE_COMMAND,
        mode: PersonaMode = PersonaMode.GROWTH,
    ): PersonaConfig = PersonaConfig(
        mode = mode,
        startSubState = startSubState,
        promptSections = mapOf(
            PromptSectionKeys.PersonaBase to "base",
            PromptSectionKeys.OutputLayerRules to "rules",
            PromptSectionKeys.PreCommandPatch to "pre",
            PromptSectionKeys.TrueSelfPatch to "true",
            PromptSectionKeys.AwakenedPatch to "awake",
            PromptSectionKeys.Heartbeat to "hb",
            PromptSectionKeys.ShockHeartbeat to "shock",
        ),
    )

private fun testRequest(userId: String = "u1") = DevelopmentMessageRequest(
        turnId = "test-$userId",
        platform = "QQ",
        scopeId = "100",
        userId = userId,
        text = "hello",
        emotionConfidence = 0.49f,
)

private fun validDelta(): Map<String, Float> = mapOf(
    "L" to 0.0f,
    "P" to 0.0f,
    "E" to 0.0f,
    "S" to 0.0f,
    "tau" to 0.0f,
    "V" to 0.0f,
    "M" to 0.0f,
    "F" to 0.0f,
)

    private class BoundaryRecordingInferenceExecutor : InferenceExecutor {
        var inferenceRunning = false
        var policyTraceAppendedInsideRun = false
        var promptManifestTraceAppendedInsideRun = false
        var preTickTraceAppendedInsideRun = false
        var affectMappingTraceAppendedInsideRun = false

        override suspend fun <T> run(block: suspend () -> T): T {
            inferenceRunning = true
            return try {
                block()
            } finally {
                inferenceRunning = false
            }
        }
    }
}

private object NoopDiaryTaskStore : DiaryTaskStore {
    override suspend fun enqueue(task: DiaryTask): Set<String> = emptySet()
    override suspend fun enqueueIfAbsent(task: DiaryTask): Set<String> = emptySet()
    override suspend fun leaseNext(sessionId: String, nowMs: Long, leaseMs: Long): DiaryTask? = null
    override suspend fun complete(taskId: String) = Unit
    override suspend fun fail(taskId: String, nowMs: Long, error: String, maxAttempts: Int) = Unit
    override suspend fun recoverExpired(nowMs: Long) = Unit
}

private object NoopDiaryCheckpointStore : DiaryCheckpointStore {
    override suspend fun read(sessionId: String): DiaryCheckpoint? = null
    override suspend fun sessions(): Set<String> = emptySet()
}

private object NoopDiaryRawMemorySource : DiaryRawMemorySource {
    override suspend fun sessionsWithRawMemories(): Set<String> = emptySet()
    override suspend fun latestRawMemory(sessionId: String): DiaryRawMemoryCursor? = null
    override suspend fun firstRawMemoryAfter(
        sessionId: String,
        coveredRawMemoryId: String?,
    ): DiaryRawMemoryCursor? = null
}
