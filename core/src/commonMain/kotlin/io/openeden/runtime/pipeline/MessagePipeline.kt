package io.openeden.runtime.pipeline

import io.openeden.bio.BioVector
import io.openeden.bio.InternalBioVector
import io.openeden.bio.VectorDelta
import io.openeden.bio.VectorMapping
import io.openeden.codebook.CodebookQuantizer
import io.openeden.codebook.HeuristicCodebookFallback
import io.openeden.codebook.QuantizationResult
import io.openeden.identity.CanonicalSubjectResolver
import io.openeden.llm.DevelopmentLlmStub
import io.openeden.llm.LlmClient
import io.openeden.llm.LlmCacheMetrics
import io.openeden.llm.LlmGenerationPolicy
import io.openeden.llm.LlmGenerationPolicyConfig
import io.openeden.llm.LlmGenerationSettings
import io.openeden.llm.LlmGroundingValidation
import io.openeden.llm.LlmOutput
import io.openeden.llm.LlmOutputValidator
import io.openeden.llm.LlmStreamEvent
import io.openeden.llm.StreamingLlmClient
import io.openeden.memory.*
import io.openeden.persona.PersonaConfig
import io.openeden.prompt.BuiltPrompt
import io.openeden.prompt.DefaultPromptBuilder
import io.openeden.prompt.PromptBuilder
import io.openeden.prompt.PromptInput
import io.openeden.prompt.PromptManifest
import io.openeden.relationship.*
import io.openeden.runtime.affect.EmotionSignal
import io.openeden.runtime.affect.OmegaState
import io.openeden.runtime.affect.PreTickEngine
import io.openeden.runtime.affect.ShockStateEngine
import io.openeden.runtime.diary.DiaryEvent
import io.openeden.runtime.diary.DiaryTaskStore
import io.openeden.runtime.diary.DiaryTriggerCoordinator
import io.openeden.runtime.diary.SessionDiaryQueue
import io.openeden.runtime.inference.DirectInferenceExecutor
import io.openeden.runtime.inference.InferenceExecutor
import io.openeden.runtime.incarnation.IncarnationStateStore
import io.openeden.runtime.incarnation.MutableIncarnationStateStore
import io.openeden.runtime.lifecycle.IncarnationLifecycleGate
import io.openeden.runtime.session.MutableSessionStateStore
import io.openeden.runtime.session.SessionStateStore
import io.openeden.runtime.session.SessionTurnGate
import io.openeden.runtime.state.*
import io.openeden.runtime.time.RuntimeClock
import io.openeden.runtime.time.SystemRuntimeClock
import io.openeden.runtime.time.TemporalContextProvider
import io.openeden.trace.*
import io.openeden.transcript.ConversationTurn
import io.openeden.transcript.AtomicTurnCommitStore
import io.openeden.transcript.InMemoryTranscriptStore
import io.openeden.transcript.TranscriptStore
import io.openeden.transcript.TurnCommitOutcome
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.withContext
import kotlin.math.log
import kotlin.time.TimeSource

class DevelopmentMessagePipeline(
    private val personaConfig: PersonaConfig,
    private val store: SessionStateStore,
    private val quantizer: CodebookQuantizer,
    private val memoryRetriever: MemoryRetriever,
    private val promptBuilder: PromptBuilder,
    private val llmClient: LlmClient,
    private val vectorWriteService: VectorWriteService,
    private val diaryQueue: SessionDiaryQueue,
    private val inferenceExecutor: InferenceExecutor,
    private val memoryStore: MemoryStore?,
    private val memoryEmbeddingModel: MemoryEmbeddingModel,
    private val centroidProvider: HomeostasisCentroidProvider,
    private val turnGate: SessionTurnGate,
    private val diaryTaskStore: DiaryTaskStore?,
    private val diaryTriggerCoordinator: DiaryTriggerCoordinator?,
    private val traceStore: TraceStore?,
    private val userAffectAnalyzer: UserAffectAnalyzer,
    private val relationshipStore: RelationshipStateStore,
    private val relationshipRoleResolver: RelationshipRoleResolver,
    private val affectInfluenceMapper: UserAffectInfluenceMapper,
    private val transcriptStore: TranscriptStore?,
    private val clock: RuntimeClock = SystemRuntimeClock,
    private val llmGenerationPolicyConfig: LlmGenerationPolicyConfig,
    private val lifecycleGate: IncarnationLifecycleGate = IncarnationLifecycleGate(),
    private val incarnationStore: IncarnationStateStore = MutableIncarnationStateStore(),
    private val canonicalSubjectResolver: CanonicalSubjectResolver = CanonicalSubjectResolver(),
) {
    private val temporalContextProvider = TemporalContextProvider(clock)

    constructor(
        personaConfig: PersonaConfig,
        store: SessionStateStore,
        quantizer: CodebookQuantizer,
        memoryRetriever: MemoryRetriever,
        promptBuilder: PromptBuilder,
        llmClient: LlmClient,
        vectorWriteService: VectorWriteService,
        diaryQueue: SessionDiaryQueue,
        inferenceExecutor: InferenceExecutor,
        memoryStore: MemoryStore?,
        memoryEmbeddingModel: MemoryEmbeddingModel,
        centroidProvider: HomeostasisCentroidProvider,
        turnGate: SessionTurnGate,
        diaryTaskStore: DiaryTaskStore?,
        diaryTriggerCoordinator: DiaryTriggerCoordinator?,
        traceStore: TraceStore?,
        userAffectAnalyzer: UserAffectAnalyzer,
        relationshipStore: RelationshipStateStore,
        relationshipRoleResolver: RelationshipRoleResolver,
        affectInfluenceMapper: UserAffectInfluenceMapper,
        transcriptStore: TranscriptStore?,
        clock: RuntimeClock = SystemRuntimeClock,
        nowMs: (() -> Long)? = null,
    ) : this(
        personaConfig = personaConfig,
        store = store,
        quantizer = quantizer,
        memoryRetriever = memoryRetriever,
        promptBuilder = promptBuilder,
        llmClient = llmClient,
        vectorWriteService = vectorWriteService,
        diaryQueue = diaryQueue,
        inferenceExecutor = inferenceExecutor,
        memoryStore = memoryStore,
        memoryEmbeddingModel = memoryEmbeddingModel,
        centroidProvider = centroidProvider,
        turnGate = turnGate,
        diaryTaskStore = diaryTaskStore,
        diaryTriggerCoordinator = diaryTriggerCoordinator,
        traceStore = traceStore,
        userAffectAnalyzer = userAffectAnalyzer,
        relationshipStore = relationshipStore,
        relationshipRoleResolver = relationshipRoleResolver,
        affectInfluenceMapper = affectInfluenceMapper,
        transcriptStore = transcriptStore,
        clock = nowMs?.let { RuntimeClock { it() } } ?: clock,
        llmGenerationPolicyConfig = LlmGenerationPolicyConfig.Default,
    )

    suspend fun handle(request: DevelopmentMessageRequest): DevelopmentMessageResult =
        handleStreaming(request)
            .filterIsInstance<DevelopmentMessageEvent.Completed>()
            .single()
            .result

    fun handleStreaming(request: DevelopmentMessageRequest): Flow<DevelopmentMessageEvent> = flow {
        lifecycleGate.withActiveTurn {
            val sessionId = "${request.platform}:${request.scopeId}"
            emit(DevelopmentMessageEvent.Stage(DevelopmentStage.PREPARING))
            val result = handleLocked(request, sessionId, ::emit)
            emit(DevelopmentMessageEvent.Completed(result))
        }
    }

    private suspend fun handleLocked(
        request: DevelopmentMessageRequest,
        sessionId: String,
        emitEvent: suspend (DevelopmentMessageEvent) -> Unit,
    ): DevelopmentMessageResult {
        val traceContext = TraceContext(
            traceId = "$sessionId:${clock.nowMs()}",
            turnId = request.turnId,
            sessionId = sessionId,
        )
        store.readOrCreate(
            sessionId = sessionId,
            personaMode = personaConfig.mode,
            personaStartSubState = personaConfig.startSubState,
        )
        val userActivityMs = clock.nowMs().takeIf { request.source == TurnSource.USER }
        if (userActivityMs != null) {
            turnGate.withSession(sessionId) {
                val scopedState = store.read(sessionId)
                if (scopedState.lastUserActivityMs == null || scopedState.lastUserActivityMs < userActivityMs) {
                    store.write(scopedState.copy(lastUserActivityMs = userActivityMs))
                }
            }
        }
        val incarnationId = transcriptStore?.activeIncarnation()?.id ?: DEVELOPMENT_INCARNATION_ID
        val canonicalSubjectId = canonicalSubjectResolver.resolve(request.platform, request.userId).value
        val initial = vectorWriteService.readOrCreateIncarnation(
            incarnationId = incarnationId,
            personaMode = personaConfig.mode,
            personaStartSubState = personaConfig.startSubState,
        )
        trace(traceContext, "state_load")
        val centroid = inferenceExecutor.run { centroidProvider.centroidFor(incarnationId) }
        trace(
            traceContext,
            "centroid",
            tags = if (centroid != initial.origin) setOf(TraceTag.CentroidUpdated) else emptySet(),
            attributes = mapOf("changed" to (centroid != initial.origin).toString()),
        )
        val current = initial.copy(origin = centroid)
        var relationshipDegraded = false
        val relationship = if (request.source == TurnSource.USER) {
            try {
                relationshipStore.readOrCreate(incarnationId, canonicalSubjectId, clock.nowMs())
            } catch (_: Throwable) {
                relationshipDegraded = true
                RelationshipState.neutral(incarnationId, canonicalSubjectId, clock.nowMs())
            }
        } else null
        if (relationship != null) trace(
            traceContext,
            "relationship_load",
            tags = setOf(if (relationshipDegraded) TraceTag.RelationshipDegraded else TraceTag.RelationshipLoaded),
        )
        val observedAffect = if (request.source == TurnSource.USER) {
            inferenceExecutor.run {
                val thymosMark = TimeSource.Monotonic.markNow()
                val result = userAffectAnalyzer.analyze(request.text)
                val engine = (userAffectAnalyzer as? InferenceEngineReporter)
                    ?.inferenceEngineDescription
                    ?: userAffectAnalyzer::class.simpleName.orEmpty().ifBlank { "unknown" }
                println("Thymos inference engine: $engine; spent ${thymosMark.elapsedNow()}")
                result
            }
        } else {
            UserAffectState.Uncertain
        }
        val emotionSignal = inferenceExecutor.run {
            if (request.emotionConfidence > 0.0f || request.emotionDelta != VectorDelta.Zero) {
                EmotionSignal(request.emotionDelta, request.emotionConfidence)
            } else {
                observedAffect.toEmotionSignal(affectInfluenceMapper)
            }
        }
        trace(
            traceContext,
            "user_affect_inference",
            tags = if (emotionSignal.confidence < PRETICK_SKIP_CONFIDENCE) {
                setOf(TraceTag.UserAffectFallback)
            } else {
                setOf(TraceTag.UserAffectInferred)
            },
            attributes = mapOf("confidence" to emotionSignal.confidence.toString()),
        )
        inferenceExecutor.run {
            trace(traceContext, "user_affect_mapping", tags = setOf(TraceTag.UserAffectMapped))
        }
        val preTick = inferenceExecutor.run {
            val result = PreTickEngine.apply(
                original = current.vector,
                signal = emotionSignal,
            )
            trace(
                traceContext,
                "pre_tick",
                attributes = mapOf("skipped" to result.skipped.toString(), "confidence" to emotionSignal.confidence.toString()),
            )
            result
        }
        val inference = inferenceExecutor.run {
            val dissonance = preTick.preTicked.derivedDissonance()
            val quantization = quantizer.quantize(preTick.preTicked, dissonance).let { result ->
                if (result.activeNodes.isEmpty()) {
                    HeuristicCodebookFallback().quantize(preTick.preTicked, dissonance)
                } else {
                    result
                }
            }
            val internalVector = VectorMapping.toInternal(preTick.preTicked, current.origin)
            val generationSettings = LlmGenerationPolicy.resolve(internalVector, llmGenerationPolicyConfig)
            val retrievalMode = RetrievalModeSelector.select(
                internalVector = internalVector,
                omegaState = current.omega,
                shockState = current.shockState,
            )
            PipelineInferenceResult(
                dissonance = dissonance,
                quantization = quantization,
                retrievalMode = retrievalMode,
                internalVector = internalVector,
                generationSettings = generationSettings,
            )
        }
        trace(traceContext, "quantization", tags = inference.quantization.traceTags)
        var recentTurns = emptyList<ConversationTurn>()
        var transcriptTags = emptySet<String>()
        if (transcriptStore != null) {
            try {
                recentTurns = transcriptStore.recentForSession(sessionId, RECENT_HISTORY_LIMIT)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Transcript is the sole source for recent_turns; memory recency is intentionally not a fallback.
                recentTurns = emptyList()
                transcriptTags = setOf(TraceTag.TranscriptDegraded)
            }
        }
        trace(traceContext, "transcript_recent", tags = transcriptTags)
        val injectedRecentTurns = recentTurns.takeLast(
            if (request.text.requiresRecentContext()) RECENT_CONTEXT_TURNS * 2 else RECENT_CONTEXT_TURNS,
        )
        val retrievalResult = inferenceExecutor.run {
            memoryRetriever.retrieve(
                RetrievalRequest(
                    sessionId = sessionId,
                    userId = request.userId,
                    userInput = request.text,
                    currentVector = preTick.preTicked,
                    origin = current.origin,
                    mode = inference.retrievalMode,
                    incarnationId = incarnationId,
                    canonicalSubjectId = canonicalSubjectId,
                    exclusionContext = MemoryExclusionContext(
                        sourceTurnIds = injectedRecentTurns.mapTo(hashSetOf()) { it.turnId },
                    ),
                ),
            )
        }
        trace(traceContext, "retrieval", tags = retrievalResult.traceTags, attributes = mapOf("mode" to retrievalResult.mode.name))
        val resolvedRelationship = relationshipRoleResolver.resolve(request.platform, request.userId)
        val prompt = promptBuilder.build(
            PromptInput(
                personaConfig = personaConfig.copy(
                    mode = current.personaMode,
                    startSubState = current.personaStartSubState,
                ),
                evolutionIndex = current.evolutionIndex,
                vectorSnapshot = preTick.preTicked,
                derivedDissonance = inference.dissonance,
                quantization = inference.quantization,
                retrievalResult = retrievalResult,
                omegaState = current.omega,
                shockState = current.shockState,
                userInput = request.text,
                temporalContext = temporalContextProvider.forTurn(request.text, current.lastUserActivityMs),
                userAffect = observedAffect,
                relationshipRole = resolvedRelationship.role,
                relationshipAddress = resolvedRelationship.address,
                relationshipState = relationship,
                recentTurns = injectedRecentTurns,
            ),
        )
        trace(traceContext, "prompt_construction")
        inferenceExecutor.run {
            trace(
                traceContext,
                "prompt_manifest",
                tags = setOf(TraceTag.PromptManifestRecorded),
                attributes = PromptManifest.from(prompt).traceAttributes(),
            )
        }
        inferenceExecutor.run {
            trace(
                traceContext,
                "llm_generation_policy",
                attributes = inference.generationSettings.maxOutputTokens?.let { maxOutputTokens ->
                    mapOf(
                        "internal_l" to inference.internalVector.l.toString(),
                        "internal_s" to inference.internalVector.s.toString(),
                        "internal_v" to inference.internalVector.v.toString(),
                        "temperature" to inference.generationSettings.temperature.toString(),
                        "verbosity" to inference.generationSettings.verbosity.name.lowercase(),
                        "max_output_tokens" to maxOutputTokens.toString(),
                    )
                } ?: mapOf(
                    "internal_l" to inference.internalVector.l.toString(),
                    "internal_s" to inference.internalVector.s.toString(),
                    "internal_v" to inference.internalVector.v.toString(),
                    "temperature" to inference.generationSettings.temperature.toString(),
                    "verbosity" to inference.generationSettings.verbosity.name.lowercase(),
                ),
            )
        }
        emitEvent(DevelopmentMessageEvent.Stage(DevelopmentStage.GENERATING))
        val llmCacheMeasurements = mutableListOf<LlmCacheMetrics>()
        val firstOutput = collectLlmOutput(prompt, inference.generationSettings, emitEvent)
        val firstCacheMetrics = firstOutput.cacheMetrics ?: LlmCacheMetrics.Unobservable
        llmCacheMeasurements += firstCacheMetrics
        trace(
            traceContext,
            "llm_inference",
            tags = firstCacheMetrics.traceTags(),
            attributes = firstCacheMetrics.traceAttributes(),
        )
        val firstValidation = LlmOutputValidator.validate(firstOutput)
        var validation = firstValidation
        var groundingTraceTags = emptySet<String>()
        if (firstValidation.isValid) {
            val firstGrounding = LlmGroundingValidation.validate(firstOutput, inference.quantization)
            if (!firstGrounding.isGrounded) {
                groundingTraceTags = setOf(
                    TraceTag.LlmGroundingFailed,
                    TraceTag.LlmGroundingRegenerated,
                )
                val repairPrompt = prompt.copy(
                    systemText = prompt.systemText +
                        "\n\n[Codebook Grounding Repair]\n" +
                        "The previous JSON was schema-valid but did not reference an active codebook node. " +
                        "Regenerate the complete JSON. In internal_logic, include one exact identifier from: " +
                        inference.quantization.activeNodes.joinToString(", ") + ".",
                )
                val repairedOutput = collectLlmOutput(repairPrompt, inference.generationSettings, emitEvent)
                val repairedCacheMetrics = repairedOutput.cacheMetrics ?: LlmCacheMetrics.Unobservable
                llmCacheMeasurements += repairedCacheMetrics
                trace(
                    traceContext,
                    "llm_regeneration",
                    tags = setOf(TraceTag.LlmGroundingRegenerated) +
                        repairedCacheMetrics.traceTags(),
                    attributes = repairedCacheMetrics.traceAttributes(),
                )
                val repairedValidation = LlmOutputValidator.validate(repairedOutput)
                if (!repairedValidation.isValid) {
                    validation = repairedValidation.copy(
                        errors = repairedValidation.errors + firstGrounding.errors,
                    )
                    groundingTraceTags += TraceTag.LlmGroundingRejected
                } else {
                    val repairedGrounding = LlmGroundingValidation.validate(
                        repairedOutput,
                        inference.quantization,
                    )
                    validation = if (repairedGrounding.isGrounded) {
                        groundingTraceTags += TraceTag.LlmGroundingRepaired
                        repairedValidation
                    } else {
                        groundingTraceTags += TraceTag.LlmGroundingRejected
                        repairedValidation.copy(
                            isValid = false,
                            output = null,
                            delta = null,
                            errors = repairedValidation.errors + repairedGrounding.errors,
                        )
                    }
                }
            }
        }
        trace(
            traceContext,
            "validation",
            status = if (validation.isValid) TraceStatus.OK else TraceStatus.FAILED,
            tags = groundingTraceTags,
            errorCode = if (validation.isValid) null else "TURN_REJECTED",
            errorSummary = validation.errors.joinToString("; "),
        )
        val aggregatedCacheMetrics = LlmCacheMetrics.aggregate(llmCacheMeasurements)
        trace(
            traceContext,
            "llm_cache",
            tags = aggregatedCacheMetrics.traceTags(),
            attributes = aggregatedCacheMetrics.traceAttributes(),
        )
        emitEvent(DevelopmentMessageEvent.Stage(DevelopmentStage.FINALIZING))
        return withContext(NonCancellable) {
        val publicTurn = if (
            request.source == TurnSource.USER &&
            validation.isValid &&
            validation.output != null &&
            validation.delta != null &&
            transcriptStore != null
        ) {
            ConversationTurn(
                turnId = request.turnId,
                incarnationId = incarnationId,
                sessionId = sessionId,
                platform = request.platform,
                scopeId = request.scopeId,
                userId = request.userId,
                userText = request.text,
                assistantText = validation.output.response,
                completedAtMs = clock.nowMs(),
            )
        } else {
            null
        }
        val write = if (validation.isValid && validation.delta != null) {
            val detectedShock = inferenceExecutor.run {
                ShockStateEngine.detectFromLlmOutput(
                    vectorDelta = validation.delta,
                    emotionConfidence = emotionSignal.confidence,
                    internalLogic = validation.output?.internalLogic.orEmpty(),
                )
            }
            vectorWriteService.commitIncarnationTurn(
                incarnationId = incarnationId,
                baseSnapshot = current.vector,
                preTickedSnapshot = preTick.preTicked,
                delta = validation.delta,
                shock = null,
                shockSignal = detectedShock,
                // Heartbeat turns evolve state but must not silence future proactive turns.
                lastUserActivityMs = userActivityMs,
                turn = publicTurn,
            )
        } else {
            VectorWriteResult(state = current, traceTags = emptySet())
        }
        trace(traceContext, "state_commit", tags = write.traceTags)
        val alreadyCommitted = write.turnCommitOutcome == TurnCommitOutcome.ALREADY_COMMITTED

        val relationshipWrite: Set<String> = if (
            !alreadyCommitted &&
            request.source == TurnSource.USER &&
            validation.isValid &&
            relationship != null
        ) {
            val evidence = relationshipEvidence(request.text)
            val updated = if (evidence != null) {
                relationship.apply(evidence, clock.nowMs())
            } else {
                relationship.copy(
                    familiarity = (relationship.familiarity + 0.005f).coerceAtMost(1.0f),
                    updatedAtMs = clock.nowMs(),
                )
            }
            try {
                relationshipStore.write(updated)
                setOf<String>(TraceTag.RelationshipUpdated)
            } catch (_: Throwable) {
                setOf<String>(TraceTag.RelationshipDegraded)
            }
        } else {
            emptySet<String>()
        }

        val sourceTags: Set<String> = if (request.source == TurnSource.HEARTBEAT) setOf(TraceTag.HeartbeatSource) else emptySet()

        var diaryOutcome = DiaryOutcome("not_triggered", emptySet())
        val memoryTraceTags = if (
            !alreadyCommitted &&
            validation.isValid &&
            validation.delta != null &&
            validation.output != null
        ) {
            inferenceExecutor.run<MemoryWriteOutcome> {
                writeMemories(
                    request = request,
                    sessionId = sessionId,
                    preTicked = preTick.preTicked,
                    origin = current.origin,
                    omega = write.state.omega,
                    delta = validation.delta,
                    response = validation.output.response,
                    incarnationId = incarnationId,
                    canonicalSubjectId = canonicalSubjectId,
                )
            }
        } else {
            MemoryWriteOutcome(null, emptySet())
        }
        val diaryDelta = validation.delta
        val diaryMemoryId = memoryTraceTags.rawMemoryId
        if (validation.isValid && diaryDelta != null && validation.output != null && diaryMemoryId != null) {
            val triggered = diaryDelta.toList().any { kotlin.math.abs(it) > 0.0f }
            val tags = if (triggered) {
                    diaryTriggerCoordinator?.onVectorDelta(
                        sessionId, diaryMemoryId, diaryDelta, requireNotNull(memoryTraceTags.rawMetadata), clock.nowMs(),
                    )
                        ?: diaryQueue.tryEnqueue(
                            DiaryEvent(
                                sessionId = sessionId,
                                traceId = "development",
                                reason = "vector_delta",
                                incarnationId = incarnationId,
                                platform = request.platform,
                                userId = request.userId,
                                canonicalSubjectId = canonicalSubjectId,
                                visibility = io.openeden.memory.MemoryVisibility.ScopeShared(sessionId),
                            ),
                        )
            } else {
                emptySet()
            }
            if (triggered) {
                diaryOutcome = DiaryOutcome(if (tags.isEmpty()) "enqueued" else "overflow", tags)
            }
        }
        trace(traceContext, "memory_write", tags = memoryTraceTags.traceTags)
        val shouldUpdatePostCentroid =
            !alreadyCommitted && validation.isValid && validation.delta != null && validation.output != null
        val updatedOrigin = if (!shouldUpdatePostCentroid) null else memoryStore?.let {
            inferenceExecutor.run { centroidProvider.centroidFor(incarnationId) }
        }
        val centroidTags: Set<String> = if (updatedOrigin != null && updatedOrigin != write.state.origin) {
            vectorWriteService.updateIncarnation(incarnationId) { it.copy(origin = updatedOrigin) }
            setOf(TraceTag.CentroidUpdated)
        } else {
            emptySet<String>()
        }
        trace(traceContext, "centroid_update", tags = centroidTags)
        trace(traceContext, "diary_publish", tags = diaryOutcome.traceTags, attributes = mapOf("outcome" to diaryOutcome.label))

        trace(
            traceContext,
            "turn",
            status = if (validation.isValid) TraceStatus.OK else TraceStatus.FAILED,
            tags = inference.quantization.traceTags + retrievalResult.traceTags + transcriptTags + centroidTags + sourceTags,
            attributes = mapOf("source" to request.source.name, "retrieval_mode" to retrievalResult.mode.name),
        )

        DevelopmentMessageResult(
            sessionId = sessionId,
            retrievalMode = retrievalResult.mode,
            traceTags = inference.quantization.traceTags +
                retrievalResult.traceTags +
                transcriptTags +
                groundingTraceTags +
                write.traceTags +
                diaryOutcome.traceTags +
                memoryTraceTags.traceTags +
                relationshipWrite +
                centroidTags +
                sourceTags,
            prompt = prompt,
            promptPreview = listOf(prompt.systemText, prompt.personaText, prompt.contextText, prompt.userText)
                .filter(String::isNotEmpty)
                .joinToString("\n\n"),
            response = validation.output?.response,
            updatedVector = write.state.vector,
            evolutionIndex = write.state.evolutionIndex,
            diaryOutcome = diaryOutcome.label,
            validationErrors = validation.errors,
            cacheMetrics = aggregatedCacheMetrics,
        )
        }
    }

    private suspend fun collectLlmOutput(
        prompt: BuiltPrompt,
        generationSettings: LlmGenerationSettings,
        emitEvent: suspend (DevelopmentMessageEvent) -> Unit,
    ): LlmOutput {
        val streaming = llmClient as? StreamingLlmClient
        if (streaming == null || !streaming.supportsStrictStructuredStreaming) {
            return llmClient.complete(prompt, generationSettings).also { output ->
                if (LlmOutputValidator.validate(output).isValid) {
                    emitEvent(DevelopmentMessageEvent.ResponseDelta(output.response))
                }
            }
        }

        var completed: LlmOutput? = null
        streaming.stream(prompt, generationSettings).collect { event ->
            when (event) {
                is LlmStreamEvent.ResponseDelta -> emitEvent(DevelopmentMessageEvent.ResponseDelta(event.text))
                is LlmStreamEvent.Completed -> {
                    check(completed == null) { "LLM stream emitted more than one completion" }
                    completed = event.output
                }
            }
        }
        println("\n${completed?.internalLogic}\n${completed?.vectorDelta}\n${completed?.response}")
        return checkNotNull(completed) { "LLM stream ended without a completed output" }
    }

    private suspend fun trace(
        context: TraceContext,
        stage: String,
        status: TraceStatus = TraceStatus.OK,
        tags: Set<String> = emptySet(),
        attributes: Map<String, String> = emptyMap(),
        errorCode: String? = null,
        errorSummary: String? = null,
    ) {
        traceStore?.let { store ->
            runCatching {
                store.append(
                    TraceSpan(
                        context = context,
                        spanId = "${context.traceId}:$stage:${clock.nowMs()}",
                        stage = stage,
                        status = status,
                        startedAtMs = clock.nowMs(),
                        finishedAtMs = clock.nowMs(),
                        tags = tags,
                        attributes = attributes,
                        errorCode = errorCode,
                        errorSummary = errorSummary,
                    ),
                )
            }
        }
    }

    private suspend fun writeMemories(
        request: DevelopmentMessageRequest,
        sessionId: String,
        preTicked: BioVector,
        origin: BioVector,
        omega: OmegaState,
        delta: VectorDelta,
        response: String,
        incarnationId: String,
        canonicalSubjectId: String,
    ): MemoryWriteOutcome {
        val store = memoryStore ?: return MemoryWriteOutcome(null, emptySet())
        val rawContent = "user=${request.userId}\ninput=${request.text}\nresponse=$response"
        val metadata = io.openeden.memory.MemoryMetadata(
            snapshot8D = preTicked,
            omegaState = omega.value,
            deltaVec = delta,
            snapshotOrigin = origin,
            userId = request.userId,
            incarnationId = incarnationId,
            sourceSessionId = sessionId,
            canonicalSubjectId = canonicalSubjectId,
            visibility = MemoryVisibility.ScopeShared(sessionId),
            platform = request.platform,
            lineage = io.openeden.memory.MemoryLineage(sourceTurnIds = listOf(request.turnId)),
            contentFingerprint = io.openeden.memory.MemoryContentFingerprint.of(rawContent),
        )
        val memoryCreatedAtMs = clock.nowMs()
        val rawId = "$sessionId:${memoryCreatedAtMs}:raw"
        val rawTags = if (omega.value < 0.75f && delta.toList().all { kotlin.math.abs(it) <= 0.05f }) {
            setOf("daily", "stable")
        } else {
            emptySet()
        }
        val rawTrace = store.write(
            MemoryEntry(
                id = rawId,
                sessionId = sessionId,
                content = rawContent,
                room = MemoryRoom.EVENT_ROOM,
                kind = MemoryKind.RAW,
                tags = rawTags,
                semanticEmbedding = memoryEmbeddingModel.embed(rawContent),
                emotionalEmbedding = memoryEmbeddingModel.embed(preTicked),
                metadata = metadata,
                createdAtMs = memoryCreatedAtMs,
            ),
        )
        // Diary generation is consumed by the asynchronous worker after the RAW commit. The user
        // turn must never create a NARRATIVE memory synchronously or wait for Diary inference.
        return MemoryWriteOutcome(rawId, rawTrace, metadata)
    }

    companion object {
        private const val RECENT_HISTORY_LIMIT = 8
        private const val RECENT_CONTEXT_TURNS = 2
        private const val DEVELOPMENT_INCARNATION_ID = "development"

        fun create(
            personaConfig: PersonaConfig,
            llmClient: LlmClient = DevelopmentLlmStub(),
            store: SessionStateStore? = null,
            incarnationStateStore: IncarnationStateStore? = null,
            vectorWriteService: VectorWriteService? = null,
            inferenceExecutor: InferenceExecutor = DirectInferenceExecutor,
            quantizer: CodebookQuantizer = HeuristicCodebookFallback(),
            memoryEmbeddingModel: MemoryEmbeddingModel = DeterministicMemoryEmbeddingModel,
            memoryStore: MemoryStore = InMemoryMemoryPalace(inferenceExecutor, embeddingModel = memoryEmbeddingModel),
            promptBuilder: PromptBuilder = DefaultPromptBuilder(),
            diaryQueue: SessionDiaryQueue = SessionDiaryQueue(),
            diaryTaskStore: DiaryTaskStore? = null,
            diaryTriggerCoordinator: DiaryTriggerCoordinator? = null,
            traceStore: TraceStore? = null,
            centroidProvider: HomeostasisCentroidProvider? = null,
            userAffectAnalyzer: UserAffectAnalyzer = DeterministicUserAffectAnalyzer(),
            relationshipStore: RelationshipStateStore = InMemoryRelationshipStateStore(),
            relationshipRoleResolver: RelationshipRoleResolver = RelationshipRoleResolver(host = null),
            affectInfluenceMapper: UserAffectInfluenceMapper = UserAffectInfluenceMapper.Default,
            transcriptStore: TranscriptStore? = store as? TranscriptStore,
            clock: RuntimeClock = SystemRuntimeClock,
            nowMs: (() -> Long)? = null,
            lifecycleGate: IncarnationLifecycleGate = IncarnationLifecycleGate(),
            canonicalSubjectResolver: CanonicalSubjectResolver = CanonicalSubjectResolver(),
        ): DevelopmentMessagePipeline = create(
            personaConfig = personaConfig,
            llmClient = llmClient,
            store = store,
            incarnationStateStore = incarnationStateStore,
            vectorWriteService = vectorWriteService,
            inferenceExecutor = inferenceExecutor,
            quantizer = quantizer,
            memoryEmbeddingModel = memoryEmbeddingModel,
            memoryStore = memoryStore,
            promptBuilder = promptBuilder,
            diaryQueue = diaryQueue,
            diaryTaskStore = diaryTaskStore,
            diaryTriggerCoordinator = diaryTriggerCoordinator,
            traceStore = traceStore,
            centroidProvider = centroidProvider,
            userAffectAnalyzer = userAffectAnalyzer,
            relationshipStore = relationshipStore,
            relationshipRoleResolver = relationshipRoleResolver,
            affectInfluenceMapper = affectInfluenceMapper,
            transcriptStore = transcriptStore,
            clock = clock,
            nowMs = nowMs,
            llmGenerationPolicyConfig = LlmGenerationPolicyConfig.Default,
            lifecycleGate = lifecycleGate,
            canonicalSubjectResolver = canonicalSubjectResolver,
        )

        fun create(
            personaConfig: PersonaConfig,
            llmClient: LlmClient = DevelopmentLlmStub(),
            store: SessionStateStore? = null,
            incarnationStateStore: IncarnationStateStore? = null,
            vectorWriteService: VectorWriteService? = null,
            inferenceExecutor: InferenceExecutor = DirectInferenceExecutor,
            quantizer: CodebookQuantizer = HeuristicCodebookFallback(),
            memoryEmbeddingModel: MemoryEmbeddingModel = DeterministicMemoryEmbeddingModel,
            memoryStore: MemoryStore = InMemoryMemoryPalace(inferenceExecutor, embeddingModel = memoryEmbeddingModel),
            promptBuilder: PromptBuilder = DefaultPromptBuilder(),
            diaryQueue: SessionDiaryQueue = SessionDiaryQueue(),
            diaryTaskStore: DiaryTaskStore? = null,
            diaryTriggerCoordinator: DiaryTriggerCoordinator? = null,
            traceStore: TraceStore? = null,
            centroidProvider: HomeostasisCentroidProvider? = null,
            userAffectAnalyzer: UserAffectAnalyzer = DeterministicUserAffectAnalyzer(),
            relationshipStore: RelationshipStateStore = InMemoryRelationshipStateStore(),
            relationshipRoleResolver: RelationshipRoleResolver = RelationshipRoleResolver(host = null),
            affectInfluenceMapper: UserAffectInfluenceMapper = UserAffectInfluenceMapper.Default,
            transcriptStore: TranscriptStore? = store as? TranscriptStore,
            clock: RuntimeClock = SystemRuntimeClock,
            nowMs: (() -> Long)? = null,
            llmGenerationPolicyConfig: LlmGenerationPolicyConfig,
            lifecycleGate: IncarnationLifecycleGate = IncarnationLifecycleGate(),
            canonicalSubjectResolver: CanonicalSubjectResolver = CanonicalSubjectResolver(),
        ): DevelopmentMessagePipeline {
            val effectiveStore = store ?: when (transcriptStore) {
                null -> MutableSessionStateStore()
                is InMemoryTranscriptStore -> MutableSessionStateStore(transcriptStore = transcriptStore)
                else -> error("A non-memory transcript store requires an explicitly co-backed session state store")
            }
            val effectiveIncarnationStore = incarnationStateStore ?: when (effectiveStore) {
                is MutableSessionStateStore -> MutableIncarnationStateStore(transcriptStore = effectiveStore.transcript)
                else -> MutableIncarnationStateStore()
            }
            val effectiveVectorWriteService = vectorWriteService
                ?: VectorWriteService(incarnationStore = effectiveIncarnationStore, inferenceExecutor = inferenceExecutor)
            require(effectiveVectorWriteService.isBackedBy(effectiveIncarnationStore)) {
                "vectorWriteService must use the same incarnation state store as the pipeline"
            }
            val effectiveTranscriptStore = transcriptStore ?: (effectiveStore as? TranscriptStore)
            if (effectiveTranscriptStore != null) {
                require(effectiveIncarnationStore.commitsTo(effectiveTranscriptStore)) {
                    "Incarnation state and transcript stores must share one atomic backend"
                }
            }
            val effectiveCentroidProvider = centroidProvider ?: SlidingWindowHomeostasisCentroidProvider(
                memoryStore = memoryStore,
                fallback = StoredOriginCentroidProvider(effectiveStore),
            )
            val effectiveClock = nowMs?.let { RuntimeClock { it() } } ?: clock
            return DevelopmentMessagePipeline(
                personaConfig = personaConfig,
                store = effectiveStore,
                quantizer = quantizer,
                memoryRetriever = memoryStore,
                promptBuilder = promptBuilder,
                llmClient = llmClient,
                vectorWriteService = effectiveVectorWriteService,
                diaryQueue = diaryQueue,
                inferenceExecutor = inferenceExecutor,
                llmGenerationPolicyConfig = llmGenerationPolicyConfig,
                memoryStore = memoryStore,
                memoryEmbeddingModel = memoryEmbeddingModel,
                centroidProvider = effectiveCentroidProvider,
                turnGate = SessionTurnGate(effectiveVectorWriteService.mutexRegistry),
                diaryTaskStore = diaryTaskStore,
                diaryTriggerCoordinator = diaryTriggerCoordinator,
                traceStore = traceStore,
                userAffectAnalyzer = userAffectAnalyzer,
                relationshipStore = relationshipStore,
                relationshipRoleResolver = relationshipRoleResolver,
                affectInfluenceMapper = affectInfluenceMapper,
                transcriptStore = effectiveTranscriptStore,
                clock = effectiveClock,
                lifecycleGate = lifecycleGate,
                incarnationStore = effectiveIncarnationStore,
                canonicalSubjectResolver = canonicalSubjectResolver,
            )
        }
    }

    private fun relationshipEvidence(text: String): RelationshipEvidence? = when {
        text.contains(Regex("不要|别这样|请停|不想说")) -> RelationshipEvidence.BOUNDARY_REQUEST
        text.contains(Regex("对不起|抱歉|误会|修正")) -> RelationshipEvidence.REPAIR
        text.contains(Regex("你记得|一直都|每次都")) -> RelationshipEvidence.REPEATED_CONSISTENCY
        else -> null
    }
}

private fun String.requiresRecentContext(): Boolean =
    contains(Regex("刚刚|刚才|上一句|上一次|之前|前面|刚才说了什么|刚刚说了什么|记得吗"))

private fun LlmCacheMetrics.traceTags(): Set<String> = when (availability) {
    io.openeden.llm.CacheMetricAvailability.REPORTED -> setOf(TraceTag.LlmCacheMeasured)
    io.openeden.llm.CacheMetricAvailability.UNOBSERVABLE -> setOf(TraceTag.LlmCacheUnobservable)
}

private data class DiaryOutcome(
    val label: String,
    val traceTags: Set<String>,
)

private data class MemoryWriteOutcome(
    val rawMemoryId: String?,
    val traceTags: Set<String>,
    val rawMetadata: MemoryMetadata? = null,
)

private data class PipelineInferenceResult(
    val dissonance: Float,
    val quantization: QuantizationResult,
    val retrievalMode: RetrievalMode,
    val internalVector: InternalBioVector,
    val generationSettings: LlmGenerationSettings,
)
