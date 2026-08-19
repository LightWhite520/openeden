package io.openeden.runtime.pipeline

import io.openeden.runtime.affect.ShockState
import io.openeden.runtime.diary.SessionDiaryQueue
import io.openeden.runtime.inference.DirectInferenceExecutor
import io.openeden.runtime.inference.InferenceExecutor
import io.openeden.runtime.inference.RecordingInferenceExecutor
import io.openeden.runtime.session.MutableSessionStateStore
import io.openeden.runtime.session.SessionTurnGate
import io.openeden.runtime.state.StoredOriginCentroidProvider
import io.openeden.runtime.state.VectorWriteService


import io.openeden.bio.BioVector
import io.openeden.bio.VectorDelta
import io.openeden.memory.RetrievalMode
import io.openeden.persona.PersonaConfig
import io.openeden.persona.PersonaMode
import io.openeden.persona.PersonaSubState
import io.openeden.llm.LlmOutput
import io.openeden.llm.LlmGenerationPolicyConfig
import io.openeden.llm.LlmGenerationSettings
import io.openeden.llm.LlmVerbosity
import io.openeden.llm.DevelopmentLlmStub
import io.openeden.memory.DeterministicMemoryEmbeddingModel
import io.openeden.memory.InMemoryMemoryPalace
import io.openeden.prompt.BuiltPrompt
import io.openeden.prompt.DefaultPromptBuilder
import io.openeden.prompt.PromptSectionKeys
import io.openeden.relationship.HostIdentity
import io.openeden.relationship.DeterministicUserAffectAnalyzer
import io.openeden.relationship.InMemoryRelationshipStateStore
import io.openeden.relationship.RelationshipRoleResolver
import io.openeden.relationship.UserAffectInfluenceMapper
import io.openeden.trace.TraceTag
import io.openeden.trace.TraceSpan
import io.openeden.trace.TraceStore
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class MessagePipelineTest {
    @Test
    fun `runs one development message turn`() = runTest {
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = testPersonaConfig(),
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
        assertEquals("not_triggered", result.diaryOutcome)
    }

    @Test
    fun `enqueues diary event when llm delta changes vector`() = runTest {
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = testPersonaConfig(),
            llmClient = object : io.openeden.llm.LlmClient {
                override suspend fun complete(prompt: BuiltPrompt): LlmOutput = LlmOutput(
                    internalLogic = "logic",
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
        assertEquals(0.7f, result.updatedVector.p)
    }

    @Test
    fun `shock back-detection persists shock state and omega jump behind confidence gate`() = runTest {
        val store = MutableSessionStateStore()
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = testPersonaConfig(),
            store = store,
            llmClient = object : io.openeden.llm.LlmClient {
                override suspend fun complete(prompt: BuiltPrompt): LlmOutput = LlmOutput(
                    internalLogic = "a severe discontinuity was inferred from the response",
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

        val state = store.read("QQ:100")
        val shock = assertNotNull(state.shockState)
        assertEquals(true, shock.active)
        assertEquals(0.4f, shock.intensity)
        assertEquals(0.06f, state.omega.value, 0.0001f)
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
        DevelopmentMessagePipeline.create(
            personaConfig = testPersonaConfig(PersonaSubState.TRUE_SELF),
            store = store,
        ).handle(testRequest())

        val result = DevelopmentMessagePipeline.create(
            personaConfig = testPersonaConfig(PersonaSubState.AWAKENED, PersonaMode.LEGACY),
            store = store,
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
            personaConfig = testPersonaConfig(),
            store = store,
            quantizer = io.openeden.codebook.HeuristicCodebookFallback(),
            memoryRetriever = memoryStore,
            promptBuilder = DefaultPromptBuilder(),
            llmClient = DevelopmentLlmStub(),
            vectorWriteService = vectorWriter,
            diaryQueue = SessionDiaryQueue(),
            inferenceExecutor = DirectInferenceExecutor,
            memoryStore = memoryStore,
            memoryEmbeddingModel = DeterministicMemoryEmbeddingModel,
            centroidProvider = StoredOriginCentroidProvider(store),
            turnGate = SessionTurnGate(vectorWriter.mutexRegistry),
            diaryTaskStore = null,
            diaryTriggerCoordinator = null,
            traceStore = null,
            userAffectAnalyzer = DeterministicUserAffectAnalyzer(),
            relationshipStore = InMemoryRelationshipStateStore(),
            relationshipRoleResolver = RelationshipRoleResolver(host = null),
            affectInfluenceMapper = UserAffectInfluenceMapper.Default,
            transcriptStore = null,
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

    private class BoundaryRecordingInferenceExecutor : InferenceExecutor {
        var inferenceRunning = false
        var policyTraceAppendedInsideRun = false

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
