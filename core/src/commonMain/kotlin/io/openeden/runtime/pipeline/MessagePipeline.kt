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
import io.openeden.llm.PersonaResponseRewriter
import io.openeden.llm.LlmStreamEvent
import io.openeden.llm.LlmValidationResult
import io.openeden.llm.StreamingLlmClient
import io.openeden.memory.*
import io.openeden.persona.PersonaConfig
import io.openeden.prompt.BuiltPrompt
import io.openeden.prompt.ConversationCacheIdentity
import io.openeden.prompt.DefaultPromptBuilder
import io.openeden.prompt.PromptBuilder
import io.openeden.prompt.PromptInput
import io.openeden.prompt.PromptManifest
import io.openeden.prompt.PromptSegmentKind
import io.openeden.relationship.*
import io.openeden.runtime.affect.EmotionSignal
import io.openeden.runtime.affect.OmegaState
import io.openeden.runtime.affect.PreTickEngine
import io.openeden.runtime.affect.ShockStateEngine
import io.openeden.runtime.diary.DiaryEvent
import io.openeden.runtime.diary.DiaryTaskStore
import io.openeden.runtime.diary.DiaryTriggerCoordinator
import io.openeden.runtime.diary.DiaryTriggerOutcome
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
import io.openeden.transcript.TurnPostCommitPlan
import io.openeden.transcript.TurnPostCommitStage
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
    private val relationshipEventEvaluator: RelationshipEventEvaluator = DeterministicRelationshipEventEvaluator(),
    private val affectInfluenceMapper: UserAffectInfluenceMapper,
    private val transcriptStore: TranscriptStore?,
    private val clock: RuntimeClock = SystemRuntimeClock,
    private val llmGenerationPolicyConfig: LlmGenerationPolicyConfig,
    private val lifecycleGate: IncarnationLifecycleGate = IncarnationLifecycleGate(),
    private val incarnationStore: IncarnationStateStore = MutableIncarnationStateStore(),
    private val canonicalSubjectResolver: CanonicalSubjectResolver = CanonicalSubjectResolver(),
    private val personaResponseRewriter: PersonaResponseRewriter? = null,
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
        personaResponseRewriter: PersonaResponseRewriter? = null,
    ) : this(
        personaConfig = personaConfig,
        store = store,
        quantizer = quantizer,
        memoryRetriever = memoryRetriever,
        promptBuilder = promptBuilder,
        llmClient = llmClient,
        personaResponseRewriter = personaResponseRewriter,
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
            turnGate.withSession(sessionId) {
                emit(DevelopmentMessageEvent.Stage(DevelopmentStage.PREPARING))
                val result = handleLocked(request, sessionId, ::emit)
                emit(DevelopmentMessageEvent.Completed(result))
            }
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
            val scopedState = store.read(sessionId)
            if (scopedState.lastUserActivityMs == null || scopedState.lastUserActivityMs < userActivityMs) {
                store.write(scopedState.copy(lastUserActivityMs = userActivityMs))
            }
        }
        val incarnationId = transcriptStore?.activeIncarnation()?.id ?: DEVELOPMENT_INCARNATION_ID
        val committedRetry = transcriptStore?.findByTurnId(request.turnId)?.also { committed ->
            require(
                committed.incarnationId == incarnationId &&
                    committed.sessionId == sessionId &&
                    committed.platform == request.platform &&
                    committed.scopeId == request.scopeId &&
                    committed.userId == request.userId &&
                    committed.userText == request.text,
            ) { "Turn ID '${request.turnId}' already exists for a different request" }
        }
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
        val relationship = if (request.source == TurnSource.USER) {
            relationshipStore.readOrCreate(incarnationId, canonicalSubjectId, clock.nowMs())
        } else null
        if (relationship != null) trace(
            traceContext,
            "relationship_load",
            tags = setOf(TraceTag.RelationshipLoaded),
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
        var promptHistory = io.openeden.transcript.PromptHistorySnapshot()
        var transcriptTags = emptySet<String>()
        if (transcriptStore != null) {
            try {
                promptHistory = transcriptStore.promptHistory(
                    sessionId = sessionId,
                    requiredTailTurns = if (request.text.requiresRecentContext()) {
                        RECENT_CONTEXT_TURNS * 2
                    } else {
                        RECENT_CONTEXT_TURNS
                    },
                    tokenBudget = PROMPT_HISTORY_CHUNK_TOKEN_BUDGET,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Transcript is the sole source for prompt history; memory recency is intentionally not a fallback.
                promptHistory = io.openeden.transcript.PromptHistorySnapshot()
                transcriptTags = setOf(TraceTag.TranscriptDegraded)
            }
        }
        trace(traceContext, "transcript_recent", tags = transcriptTags)
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
                        sourceTurnIds = promptHistory.sourceTurnIds,
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
                conversationCacheIdentity = ConversationCacheIdentity.fromAuthoritativeSessionId(sessionId),
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
                promptHistory = promptHistory,
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
        val firstCompletion = committedRetry?.let { committed ->
            CollectedLlmOutput(
                output = committed.toReplayOutput(inference.quantization.activeNodes.firstOrNull()),
                responseChunks = emptyList(),
            )
        } ?: collectLlmOutput(prompt, inference.generationSettings)
        val firstOutput = firstCompletion.output
        var responseChunkCandidates = firstCompletion.responseChunks
        if (committedRetry == null) {
            val firstCacheMetrics = firstOutput.cacheMetrics ?: LlmCacheMetrics.Unobservable
            llmCacheMeasurements += firstCacheMetrics
            trace(
                traceContext,
                "llm_inference",
                tags = firstCacheMetrics.traceTags(),
                attributes = firstCacheMetrics.traceAttributes(),
            )
        } else {
            trace(
                traceContext,
                "turn_replay",
                tags = setOf(TraceTag.TranscriptRetry),
                attributes = mapOf("committed_turn_id" to committedRetry.turnId),
            )
        }
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
                val repairPrompt = prompt.appendDynamic(
                    PromptSegmentKind.BIO,
                    "[Codebook Grounding Repair]\n" +
                        "The previous JSON was schema-valid but did not reference an active codebook node. " +
                        "Regenerate the complete JSON. In internal_logic, include one exact identifier from: " +
                        inference.quantization.activeNodes.joinToString(", ") + ".",
                )
                val repairedCompletion = collectLlmOutput(repairPrompt, inference.generationSettings)
                val repairedOutput = repairedCompletion.output
                responseChunkCandidates = repairedCompletion.responseChunks
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
        if (validation.isValid) {
            validation = applyPublicVoicePolicy(
                output = requireNotNull(validation.output),
                prompt = prompt,
                generationSettings = inference.generationSettings,
                recentAssistantResponses = promptHistory.flattenItems()
                    .filter { it.role == "assistant" && it.turnId != request.turnId }
                    .map { it.text }
                    .takeLast(RECENT_ASSISTANT_VALIDATION_TURNS),
                llmCacheMeasurements = llmCacheMeasurements,
            )
        }
        trace(
            traceContext,
            "validation",
            status = if (validation.isValid) TraceStatus.OK else TraceStatus.FAILED,
            tags = groundingTraceTags,
            errorCode = if (validation.isValid) null else "TURN_REJECTED",
            errorSummary = validation.errors.joinToString("; "),
        )
        val aggregatedCacheMetrics = if (llmCacheMeasurements.isEmpty()) {
            LlmCacheMetrics.Unobservable
        } else {
            LlmCacheMetrics.aggregate(llmCacheMeasurements)
        }
        if (llmCacheMeasurements.isNotEmpty()) {
            trace(
                traceContext,
                "llm_cache",
                tags = aggregatedCacheMetrics.traceTags(),
                attributes = aggregatedCacheMetrics.traceAttributes(),
            )
        }
        emitEvent(DevelopmentMessageEvent.Stage(DevelopmentStage.FINALIZING))
        val validatedOutput = validation.output
        val validatedDelta = validation.delta
        val publicTurn = if (
            request.source == TurnSource.USER &&
            validation.isValid &&
            validatedOutput != null &&
            validatedDelta != null &&
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
                // Replay policy repair is delivery-only; the committed transcript remains authoritative.
                assistantText = committedRetry?.assistantText ?: validatedOutput.response,
                completedAtMs = committedRetry?.completedAtMs ?: clock.nowMs(),
            )
        } else {
            null
        }
        val preparedRawMemory = if (committedRetry == null && publicTurn != null && validatedDelta != null) {
            inferenceExecutor.run {
                prepareRawMemory(
                    request = request,
                    sessionId = sessionId,
                    preTicked = preTick.preTicked,
                    origin = current.origin,
                    omega = current.omega,
                    delta = validatedDelta,
                    response = publicTurn.assistantText,
                    incarnationId = incarnationId,
                    canonicalSubjectId = canonicalSubjectId,
                )
            }
        } else {
            null
        }
        val postCommitPlan = publicTurn?.let { turn ->
            if (committedRetry != null) {
                transcriptStore?.postCommitState(turn.turnId)?.plan ?: TurnPostCommitPlan(turn.turnId)
            } else {
                TurnPostCommitPlan(
                    turnId = turn.turnId,
                    relationshipTurn = relationship?.let {
                        RelationshipTurn(
                            sourceTurnId = turn.turnId,
                            incarnationId = incarnationId,
                            subjectId = canonicalSubjectId,
                            userText = turn.userText,
                            assistantText = turn.assistantText,
                            completedAtMs = turn.completedAtMs,
                        )
                    },
                    rawMemory = preparedRawMemory,
                )
            }
        }
        val write = if (validation.isValid && validatedDelta != null) {
            val detectedShock = inferenceExecutor.run {
                ShockStateEngine.detectFromLlmOutput(
                    vectorDelta = validatedDelta,
                    emotionConfidence = emotionSignal.confidence,
                    internalLogic = validatedOutput?.internalLogic.orEmpty(),
                )
            }
            withContext(NonCancellable) {
                vectorWriteService.commitIncarnationTurn(
                    incarnationId = incarnationId,
                    baseSnapshot = current.vector,
                    preTickedSnapshot = preTick.preTicked,
                    delta = validatedDelta,
                    shockSignal = detectedShock,
                    // Heartbeat turns evolve state but must not silence future proactive turns.
                    lastUserActivityMs = userActivityMs,
                    turn = publicTurn,
                    postCommitPlan = postCommitPlan,
                )
            }
        } else {
            VectorWriteResult(state = current, traceTags = emptySet())
        }
        trace(traceContext, "state_commit", tags = write.traceTags)
        val alreadyCommitted = write.turnCommitOutcome == TurnCommitOutcome.ALREADY_COMMITTED

        val sourceTags: Set<String> = if (request.source == TurnSource.HEARTBEAT) setOf(TraceTag.HeartbeatSource) else emptySet()
        var relationshipWrite = emptySet<String>()
        var diaryOutcome = DiaryOutcome("not_triggered", emptySet())
        var memoryTraceTags = MemoryWriteOutcome(null, emptySet())
        var centroidTags = emptySet<String>()
        val durablePostCommit = if (
            publicTurn != null &&
            (write.turnCommitOutcome == TurnCommitOutcome.INSERTED || alreadyCommitted)
        ) {
            checkNotNull(transcriptStore?.postCommitState(publicTurn.turnId)) {
                "Committed turn '${publicTurn.turnId}' is missing its post-commit plan"
            }
        } else {
            null
        }
        if (durablePostCommit != null) {
            val committedTurn = requireNotNull(publicTurn)
            val completeStage: suspend (TurnPostCommitStage) -> Unit = { stage ->
                withContext(NonCancellable) {
                    requireNotNull(transcriptStore).markPostCommitStageCompleted(committedTurn.turnId, stage)
                }
            }
            for (stage in durablePostCommit.pendingStages) {
                when (stage) {
                    TurnPostCommitStage.RELATIONSHIP -> {
                        val evaluation = durablePostCommit.plan.relationshipEvaluation ?: run {
                            val candidate = relationshipEventEvaluator.evaluate(
                                requireNotNull(durablePostCommit.plan.relationshipTurn) {
                                    "Pending relationship stage is missing its evaluation input"
                                },
                            )
                            withContext(NonCancellable) {
                                requireNotNull(transcriptStore).persistRelationshipEvaluation(
                                    committedTurn.turnId,
                                    candidate,
                                )
                            }
                        }
                        trace(
                            traceContext,
                            "relationship_evaluation",
                            attributes = mapOf(
                                "confidence" to evaluation.confidence.toString(),
                                "committable_event_count" to evaluation.committableEvents.size.toString(),
                            ),
                        )
                        val events = if (evaluation.confidence >= 0.75f) {
                            evaluation.committableEvents.ifEmpty {
                                listOf(
                                    RelationshipEvent(
                                        eventId = "${committedTurn.turnId}:${RelationshipEventType.ACQUAINTANCE.name}",
                                        incarnationId = committedTurn.incarnationId,
                                        canonicalSubjectId = canonicalSubjectId,
                                        sourceTurnId = committedTurn.turnId,
                                        type = RelationshipEventType.ACQUAINTANCE,
                                        confidence = evaluation.confidence,
                                        evidenceDigest = "committed interaction",
                                        createdAtMs = committedTurn.completedAtMs,
                                    ),
                                )
                            }
                        } else {
                            emptyList()
                        }
                        events.forEach { relationshipStore.append(it) }
                        relationshipWrite = buildSet {
                            if (evaluation.confidence >= 0.75f) add(TraceTag.RelationshipUpdated)
                        }
                        completeStage(stage)
                    }
                    TurnPostCommitStage.RAW_MEMORY -> {
                        memoryTraceTags = inferenceExecutor.run {
                            writeRawMemory(requireNotNull(durablePostCommit.plan.rawMemory))
                        }
                        completeStage(stage)
                    }
                    TurnPostCommitStage.DIARY -> {
                        val rawMemory = requireNotNull(durablePostCommit.plan.rawMemory)
                        val triggerOutcome = diaryTriggerCoordinator?.onVectorDelta(
                            sessionId,
                            rawMemory.id,
                            rawMemory.metadata.deltaVec,
                            rawMemory.metadata,
                            clock.nowMs(),
                        ) ?: DiaryTriggerOutcome.fromTraceTags(
                            diaryQueue.tryEnqueue(
                                DiaryEvent(
                                    sessionId = sessionId,
                                    traceId = rawMemory.id,
                                    reason = "vector_delta",
                                    incarnationId = incarnationId,
                                    platform = request.platform,
                                    userId = request.userId,
                                    canonicalSubjectId = canonicalSubjectId,
                                    visibility = rawMemory.metadata.visibility,
                                ),
                            ),
                        )
                        diaryOutcome = triggerOutcome.toPipelineOutcome()
                        completeStage(stage)
                    }
                    TurnPostCommitStage.CENTROID -> {
                        val updatedOrigin = memoryStore?.let {
                            inferenceExecutor.run { centroidProvider.centroidFor(incarnationId) }
                        }
                        if (updatedOrigin != null && updatedOrigin != write.state.origin) {
                            vectorWriteService.updateIncarnation(incarnationId) { it.copy(origin = updatedOrigin) }
                            centroidTags = setOf(TraceTag.CentroidUpdated)
                        }
                        completeStage(stage)
                    }
                }
            }
            val remainingStages = requireNotNull(transcriptStore)
                .postCommitState(committedTurn.turnId)
                ?.pendingStages
                ?: error("Committed turn '${committedTurn.turnId}' lost its post-commit plan")
            check(remainingStages.isEmpty()) {
                "Committed turn '${committedTurn.turnId}' still has pending post-commit stages: $remainingStages"
            }
        } else if (
            !alreadyCommitted &&
            validation.isValid &&
            validation.delta != null &&
            validation.output != null
        ) {
            val rawMemory = inferenceExecutor.run {
                prepareRawMemory(
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
            if (rawMemory != null) {
                memoryTraceTags = inferenceExecutor.run { writeRawMemory(rawMemory) }
                val triggered = validation.delta.toList().any { kotlin.math.abs(it) > 0.0f }
                if (triggered) {
                    val triggerOutcome = diaryTriggerCoordinator?.onVectorDelta(
                        sessionId, rawMemory.id, validation.delta, rawMemory.metadata, clock.nowMs(),
                    ) ?: DiaryTriggerOutcome.fromTraceTags(
                        diaryQueue.tryEnqueue(
                            DiaryEvent(
                                sessionId = sessionId,
                                traceId = rawMemory.id,
                                reason = "vector_delta",
                                incarnationId = incarnationId,
                                platform = request.platform,
                                userId = request.userId,
                                canonicalSubjectId = canonicalSubjectId,
                                visibility = rawMemory.metadata.visibility,
                            ),
                        ),
                    )
                    diaryOutcome = triggerOutcome.toPipelineOutcome()
                }
                val updatedOrigin = inferenceExecutor.run { centroidProvider.centroidFor(incarnationId) }
                if (updatedOrigin != write.state.origin) {
                    vectorWriteService.updateIncarnation(incarnationId) { it.copy(origin = updatedOrigin) }
                    centroidTags = setOf(TraceTag.CentroidUpdated)
                }
            }
        }
        trace(traceContext, "memory_write", tags = memoryTraceTags.traceTags)
        trace(traceContext, "centroid_update", tags = centroidTags)
        trace(traceContext, "diary_publish", tags = diaryOutcome.traceTags, attributes = mapOf("outcome" to diaryOutcome.label))

        trace(
            traceContext,
            "turn",
            status = if (validation.isValid) TraceStatus.OK else TraceStatus.FAILED,
            tags = inference.quantization.traceTags + retrievalResult.traceTags + transcriptTags + centroidTags + sourceTags,
            attributes = mapOf("source" to request.source.name, "retrieval_mode" to retrievalResult.mode.name),
        )

        val result = DevelopmentMessageResult(
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
            promptPreview = prompt.textPreview(),
            response = validation.output?.response,
            updatedVector = write.state.vector,
            evolutionIndex = write.state.evolutionIndex,
            diaryOutcome = diaryOutcome.label,
            validationErrors = validation.errors,
            cacheMetrics = aggregatedCacheMetrics,
        )
        if (validation.isValid) {
            responseChunksForDelivery(
                candidates = responseChunkCandidates,
                approvedResponse = requireNotNull(validation.output).response,
            ).forEach { chunk ->
                emitEvent(DevelopmentMessageEvent.ResponseDelta(chunk))
            }
        }
        return result
    }

    private suspend fun applyPublicVoicePolicy(
        output: LlmOutput,
        prompt: BuiltPrompt,
        generationSettings: LlmGenerationSettings,
        recentAssistantResponses: List<String>,
        llmCacheMeasurements: MutableList<LlmCacheMetrics>,
    ): LlmValidationResult {
        val schemaValidation = LlmOutputValidator.validate(output)
        if (!schemaValidation.isValid) return schemaValidation

        val policy = personaConfig.outputPolicy
        val policyValidation = LlmOutputValidator.validate(output, policy, recentAssistantResponses)
        if (policyValidation.isValid) return policyValidation

        val rewriteInput = output.copy(cacheMetrics = null)
        val rewritten = if (personaResponseRewriter != null) {
            personaResponseRewriter.rewriteResponseOnly(rewriteInput, policy)
        } else {
            collectLlmOutput(
                publicVoiceRewritePrompt(prompt, rewriteInput),
                generationSettings,
            ).output
        }
        llmCacheMeasurements += rewritten.cacheMetrics ?: LlmCacheMetrics.Unobservable
        val responseOnly = output.copy(response = rewritten.response)
        return LlmOutputValidator.validate(responseOnly, policy, recentAssistantResponses)
    }

    private fun publicVoiceRewritePrompt(prompt: BuiltPrompt, output: LlmOutput): BuiltPrompt = prompt.appendDynamic(
        PromptSegmentKind.TEMPORAL,
        "[Public Response Rewrite]\n" +
            "The previous output is schema-valid but its public response violates the active persona output policy. " +
            "Rewrite response exactly once and return the complete required JSON schema. " +
            "Keep internal_logic and every vector_delta value exactly unchanged.\n\n" +
            "[Previous Structured Output]\n" +
            "internal_logic: ${output.internalLogic}\n" +
            "vector_delta: ${output.vectorDelta}\n" +
            "response: ${output.response}",
    )

    private suspend fun collectLlmOutput(
        prompt: BuiltPrompt,
        generationSettings: LlmGenerationSettings,
    ): CollectedLlmOutput {
        val streaming = llmClient as? StreamingLlmClient
        if (streaming == null || !streaming.supportsStrictStructuredStreaming) {
            return CollectedLlmOutput(
                output = llmClient.complete(prompt, generationSettings),
                responseChunks = emptyList(),
            )
        }

        var completed: LlmOutput? = null
        val responseChunks = mutableListOf<String>()
        streaming.stream(prompt, generationSettings).collect { event ->
            when (event) {
                is LlmStreamEvent.ResponseDelta -> responseChunks += event.text
                is LlmStreamEvent.Completed -> {
                    check(completed == null) { "LLM stream emitted more than one completion" }
                    completed = event.output
                }
            }
        }
        return CollectedLlmOutput(
            output = checkNotNull(completed) { "LLM stream ended without a completed output" },
            responseChunks = responseChunks,
        )
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

    private suspend fun prepareRawMemory(
        request: DevelopmentMessageRequest,
        sessionId: String,
        preTicked: BioVector,
        origin: BioVector,
        omega: OmegaState,
        delta: VectorDelta,
        response: String,
        incarnationId: String,
        canonicalSubjectId: String,
    ): MemoryEntry? {
        if (memoryStore == null) return null
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
            visibility = request.memoryVisibility(sessionId, canonicalSubjectId),
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
        return MemoryEntry(
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
        )
    }

    private suspend fun writeRawMemory(entry: MemoryEntry): MemoryWriteOutcome {
        val store = memoryStore ?: return MemoryWriteOutcome(null, emptySet())
        val rawTrace = store.write(entry)
        // Diary generation is consumed by the asynchronous worker after the RAW commit. The user
        // turn must never create a NARRATIVE memory synchronously or wait for Diary inference.
        return MemoryWriteOutcome(entry.id, rawTrace, entry.metadata)
    }

    companion object {
        private const val RECENT_CONTEXT_TURNS = 2
        private const val RECENT_ASSISTANT_VALIDATION_TURNS = 8
        private const val PROMPT_HISTORY_CHUNK_TOKEN_BUDGET = 4_096
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
            relationshipEventEvaluator: RelationshipEventEvaluator = DeterministicRelationshipEventEvaluator(),
            affectInfluenceMapper: UserAffectInfluenceMapper = UserAffectInfluenceMapper.Default,
            transcriptStore: TranscriptStore? = store as? TranscriptStore,
            clock: RuntimeClock = SystemRuntimeClock,
            nowMs: (() -> Long)? = null,
            lifecycleGate: IncarnationLifecycleGate = IncarnationLifecycleGate(),
            canonicalSubjectResolver: CanonicalSubjectResolver = CanonicalSubjectResolver(),
            personaResponseRewriter: PersonaResponseRewriter? = null,
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
            relationshipEventEvaluator = relationshipEventEvaluator,
            affectInfluenceMapper = affectInfluenceMapper,
            transcriptStore = transcriptStore,
            clock = clock,
            nowMs = nowMs,
            llmGenerationPolicyConfig = LlmGenerationPolicyConfig.Default,
            lifecycleGate = lifecycleGate,
            canonicalSubjectResolver = canonicalSubjectResolver,
            personaResponseRewriter = personaResponseRewriter,
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
            relationshipEventEvaluator: RelationshipEventEvaluator = DeterministicRelationshipEventEvaluator(),
            affectInfluenceMapper: UserAffectInfluenceMapper = UserAffectInfluenceMapper.Default,
            transcriptStore: TranscriptStore? = store as? TranscriptStore,
            clock: RuntimeClock = SystemRuntimeClock,
            nowMs: (() -> Long)? = null,
            llmGenerationPolicyConfig: LlmGenerationPolicyConfig,
            lifecycleGate: IncarnationLifecycleGate = IncarnationLifecycleGate(),
            canonicalSubjectResolver: CanonicalSubjectResolver = CanonicalSubjectResolver(),
            personaResponseRewriter: PersonaResponseRewriter? = null,
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
                relationshipEventEvaluator = relationshipEventEvaluator,
                affectInfluenceMapper = affectInfluenceMapper,
                transcriptStore = effectiveTranscriptStore,
                clock = effectiveClock,
                lifecycleGate = lifecycleGate,
                incarnationStore = effectiveIncarnationStore,
                canonicalSubjectResolver = canonicalSubjectResolver,
                personaResponseRewriter = personaResponseRewriter,
            )
        }
    }

}

private fun String.requiresRecentContext(): Boolean =
    contains(Regex("刚刚|刚才|上一句|上一次|之前|前面|刚才说了什么|刚刚说了什么|记得吗"))

private fun DevelopmentMessageRequest.memoryVisibility(
    sessionId: String,
    canonicalSubjectId: String,
): MemoryVisibility = if (userId.isNotBlank() && scopeId == userId) {
    MemoryVisibility.PrivateSubject(canonicalSubjectId)
} else {
    MemoryVisibility.ScopeShared(sessionId)
}

private fun ConversationTurn.toReplayOutput(activeNode: String?): LlmOutput = LlmOutput(
    internalLogic = "Committed turn replay${activeNode?.let { "; active node $it" }.orEmpty()}",
    vectorDelta = mapOf(
        "L" to 0.0f,
        "P" to 0.0f,
        "E" to 0.0f,
        "S" to 0.0f,
        "tau" to 0.0f,
        "V" to 0.0f,
        "M" to 0.0f,
        "F" to 0.0f,
    ),
    response = assistantText,
)

private fun responseChunksForDelivery(
    candidates: List<String>,
    approvedResponse: String,
): List<String> {
    val nonEmptyCandidates = candidates.filter(String::isNotEmpty)
    return if (nonEmptyCandidates.joinToString(separator = "") == approvedResponse) {
        nonEmptyCandidates
    } else {
        listOf(approvedResponse)
    }
}

private data class CollectedLlmOutput(
    val output: LlmOutput,
    val responseChunks: List<String>,
)

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

private fun DiaryTriggerOutcome.toPipelineOutcome(): DiaryOutcome = DiaryOutcome(
    label = name.lowercase(),
    traceTags = traceTags,
)

private data class PipelineInferenceResult(
    val dissonance: Float,
    val quantization: QuantizationResult,
    val retrievalMode: RetrievalMode,
    val internalVector: InternalBioVector,
    val generationSettings: LlmGenerationSettings,
)
