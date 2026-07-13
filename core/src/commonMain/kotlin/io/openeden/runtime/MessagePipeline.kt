package io.openeden.runtime

import io.openeden.bio.BioVector
import io.openeden.bio.VectorDelta
import io.openeden.bio.VectorMapping
import io.openeden.codebook.CodebookQuantizer
import io.openeden.codebook.HeuristicCodebookFallback
import io.openeden.codebook.QuantizationResult
import io.openeden.llm.DevelopmentLlmStub
import io.openeden.llm.LlmClient
import io.openeden.llm.LlmOutputValidator
import io.openeden.memory.InMemoryMemoryPalace
import io.openeden.memory.DeterministicMemoryEmbeddingModel
import io.openeden.memory.MemoryEmbeddingModel
import io.openeden.memory.MemoryEntry
import io.openeden.memory.MemoryKind
import io.openeden.memory.MemoryRoom
import io.openeden.memory.MemoryStore
import io.openeden.memory.MemoryRetriever
import io.openeden.memory.RetrievalMode
import io.openeden.memory.RetrievalModeSelector
import io.openeden.memory.RetrievalRequest
import io.openeden.memory.RetrievalResult
import io.openeden.persona.PersonaConfig
import io.openeden.prompt.BuiltPrompt
import io.openeden.prompt.DefaultPromptBuilder
import io.openeden.prompt.PromptBuilder
import io.openeden.prompt.PromptInput
import io.openeden.trace.TraceTag
import io.openeden.trace.TraceContext
import io.openeden.trace.TraceSpan
import io.openeden.trace.TraceStatus
import io.openeden.trace.TraceStore
import io.openeden.relationship.DeterministicUserAffectAnalyzer
import io.openeden.relationship.RelationshipEvidence
import io.openeden.relationship.RelationshipState
import io.openeden.relationship.RelationshipStateStore
import io.openeden.relationship.InMemoryRelationshipStateStore
import io.openeden.relationship.UserAffectAnalyzer
import io.openeden.relationship.UserAffectInfluenceMapper
import kotlin.time.Clock

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
    private val affectInfluenceMapper: UserAffectInfluenceMapper,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    suspend fun handle(request: DevelopmentMessageRequest): DevelopmentMessageResult {
        val sessionId = "${request.platform}:${request.scopeId}"
        return turnGate.withSession(sessionId) {
            handleLocked(request, sessionId)
        }
    }

    private suspend fun handleLocked(
        request: DevelopmentMessageRequest,
        sessionId: String,
    ): DevelopmentMessageResult {
        val traceContext = TraceContext(
            traceId = "$sessionId:${nowMs()}",
            turnId = "$sessionId:${nowMs()}:turn",
            sessionId = sessionId,
        )
        val initial = store.readOrCreate(sessionId)
        trace(traceContext, "state_load")
        val centroid = inferenceExecutor.run { centroidProvider.centroidFor(sessionId) }
        if (centroid != initial.origin) {
            vectorWriteService.updateLocked(sessionId) { it.copy(origin = centroid) }
        }
        trace(traceContext, "centroid", attributes = mapOf("updated" to (centroid != initial.origin).toString()))
        val current = store.read(sessionId)
        var relationshipDegraded = false
        val relationship = if (request.source == TurnSource.USER) {
            try {
                relationshipStore.readOrCreate(sessionId, request.userId, nowMs())
            } catch (_: Throwable) {
                relationshipDegraded = true
                RelationshipState.neutral(sessionId, request.userId, nowMs())
            }
        } else null
        if (relationship != null) trace(
            traceContext,
            "relationship_load",
            tags = setOf(if (relationshipDegraded) TraceTag.RelationshipDegraded else TraceTag.RelationshipLoaded),
        )
        val observedAffect = if (request.source == TurnSource.USER) {
            inferenceExecutor.run { userAffectAnalyzer.analyze(request.text) }
        } else {
            io.openeden.relationship.UserAffectState.Uncertain
        }
        val emotionSignal = if (request.emotionConfidence > 0.0f || request.emotionDelta != VectorDelta.Zero) {
            EmotionSignal(request.emotionDelta, request.emotionConfidence)
        } else {
            observedAffect.toEmotionSignal(affectInfluenceMapper)
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
        trace(traceContext, "user_affect_mapping", tags = setOf(TraceTag.UserAffectMapped))
        val preTick = PreTickEngine.apply(
            original = current.vector,
            signal = emotionSignal,
        )
        trace(
            traceContext,
            "pre_tick",
            attributes = mapOf("skipped" to preTick.skipped.toString(), "confidence" to emotionSignal.confidence.toString()),
        )
        val inference = inferenceExecutor.run {
            val dissonance = preTick.preTicked.derivedDissonance()
            val quantization = quantizer.quantize(preTick.preTicked, dissonance)
            val internalVector = VectorMapping.toInternal(preTick.preTicked, current.origin)
            val retrievalMode = RetrievalModeSelector.select(
                internalVector = internalVector,
                omegaState = current.omega,
                shockState = current.shockState,
            )
            PipelineInferenceResult(
                dissonance = dissonance,
                quantization = quantization,
                retrievalMode = retrievalMode,
            )
        }
        trace(traceContext, "quantization", tags = inference.quantization.traceTags)
        val retrievalResult = inferenceExecutor.run {
            memoryRetriever.retrieve(
                RetrievalRequest(
                    sessionId = sessionId,
                    userId = request.userId,
                    userInput = request.text,
                    currentVector = preTick.preTicked,
                    origin = current.origin,
                    mode = inference.retrievalMode,
                ),
            )
        }.let { result ->
            val recent = memoryStore?.recent(sessionId, RECENT_HISTORY_LIMIT).orEmpty()
            result.copy(recentMemories = recent)
        }
        trace(traceContext, "retrieval", tags = retrievalResult.traceTags, attributes = mapOf("mode" to retrievalResult.mode.name))
        val prompt = promptBuilder.build(
            PromptInput(
                personaConfig = personaConfig,
                evolutionIndex = current.evolutionIndex,
                vectorSnapshot = preTick.preTicked,
                derivedDissonance = inference.dissonance,
                quantization = inference.quantization,
                retrievalResult = retrievalResult,
                omegaState = current.omega,
                shockState = current.shockState,
                userInput = request.text,
                userAffect = observedAffect,
                relationshipState = relationship,
            ),
        )
        trace(traceContext, "prompt_construction")
        val llmOutput = llmClient.complete(prompt)
        trace(traceContext, "llm_inference")
        val validation = LlmOutputValidator.validate(llmOutput)
        trace(
            traceContext,
            "validation",
            status = if (validation.isValid) TraceStatus.OK else TraceStatus.FAILED,
            errorCode = if (validation.isValid) null else "TURN_REJECTED",
            errorSummary = validation.errors.joinToString("; "),
        )
        val write = if (validation.isValid && validation.delta != null) {
            val vectorWrite = vectorWriteService.applyLlmDeltaLocked(
                sessionId = sessionId,
                preTickedSnapshot = preTick.preTicked,
                delta = validation.delta,
            )
            val detectedShock = inferenceExecutor.run {
                ShockStateEngine.detectFromLlmOutput(
                    vectorDelta = validation.delta,
                    emotionConfidence = emotionSignal.confidence,
                    internalLogic = validation.output?.internalLogic.orEmpty(),
                )
            }
            val shockWrite = detectedShock?.let { shock ->
                vectorWriteService.applyShockLocked(sessionId, shock)
            }
            val applied = shockWrite?.copy(
                traceTags = vectorWrite.traceTags + shockWrite.traceTags,
            ) ?: vectorWrite
            // USER turns reset the silence clock that gates heartbeats (§9.3); heartbeat turns
            // evolve state but must not, or ATRI would silence her own proactive impulse.
            if (request.source == TurnSource.USER) {
                val refreshed = vectorWriteService.markUserActivityLocked(sessionId, nowMs())
                applied.copy(state = refreshed)
            } else {
                applied
            }
        } else {
            VectorWriteResult(state = current, traceTags = emptySet())
        }
        trace(traceContext, "state_commit", tags = write.traceTags)

        val relationshipWrite: Set<String> = if (request.source == TurnSource.USER && validation.isValid && relationship != null) {
            val evidence = relationshipEvidence(request.text)
            val updated = if (evidence != null) {
                relationship.apply(evidence, nowMs())
            } else {
                relationship.copy(
                    familiarity = (relationship.familiarity + 0.005f).coerceAtMost(1.0f),
                    updatedAtMs = nowMs(),
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
        val memoryTraceTags = if (validation.isValid && validation.delta != null && validation.output != null) {
            inferenceExecutor.run<MemoryWriteOutcome> {
                writeMemories(
                    request = request,
                    sessionId = sessionId,
                    preTicked = preTick.preTicked,
                    origin = current.origin,
                    omega = write.state.omega,
                    delta = validation.delta,
                    response = validation.output.response,
                )
            }
        } else {
            MemoryWriteOutcome(null, emptySet())
        }
        if (validation.isValid && validation.delta != null && validation.delta.toList().any { kotlin.math.abs(it) > 0.0f } && validation.output != null && memoryTraceTags.rawMemoryId != null) {
            val tags = diaryTriggerCoordinator?.onVectorDelta(sessionId, memoryTraceTags.rawMemoryId, validation.delta, nowMs())
                ?: diaryQueue.tryEnqueue(DiaryEvent(sessionId, "development", "vector_delta"))
            diaryOutcome = DiaryOutcome(if (tags.isEmpty()) "enqueued" else "overflow", tags)
        }
        trace(traceContext, "memory_write", tags = memoryTraceTags.traceTags)
        val updatedOrigin = memoryStore?.let {
            inferenceExecutor.run { centroidProvider.centroidFor(sessionId) }
        }
        val centroidTags: Set<String> = if (updatedOrigin != null && updatedOrigin != write.state.origin) {
            vectorWriteService.updateLocked(sessionId) { it.copy(origin = updatedOrigin) }
            setOf(TraceTag.CentroidUpdated)
        } else {
            emptySet<String>()
        }
        trace(traceContext, "diary_publish", tags = diaryOutcome.traceTags, attributes = mapOf("outcome" to diaryOutcome.label))

        trace(
            traceContext,
            "turn",
            status = if (validation.isValid) TraceStatus.OK else TraceStatus.FAILED,
            tags = inference.quantization.traceTags + retrievalResult.traceTags + sourceTags,
            attributes = mapOf("source" to request.source.name, "retrieval_mode" to retrievalResult.mode.name),
        )

        return DevelopmentMessageResult(
            sessionId = sessionId,
            retrievalMode = retrievalResult.mode,
            traceTags = inference.quantization.traceTags +
                retrievalResult.traceTags +
                write.traceTags +
                diaryOutcome.traceTags +
                memoryTraceTags.traceTags +
                relationshipWrite +
                centroidTags +
                sourceTags,
            prompt = prompt,
            promptPreview = listOf(prompt.systemText, prompt.personaText, prompt.userText).joinToString("\n\n"),
            response = validation.output?.response,
            updatedVector = store.read(sessionId).vector,
            evolutionIndex = store.read(sessionId).evolutionIndex,
            diaryOutcome = diaryOutcome.label,
            validationErrors = validation.errors,
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
                        spanId = "${context.traceId}:$stage:${nowMs()}",
                        stage = stage,
                        status = status,
                        startedAtMs = nowMs(),
                        finishedAtMs = nowMs(),
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
    ): MemoryWriteOutcome {
        val store = memoryStore ?: return MemoryWriteOutcome(null, emptySet())
        val metadata = io.openeden.memory.MemoryMetadata(
            snapshot8D = preTicked,
            omegaState = omega.value,
            deltaVec = delta,
            snapshotOrigin = origin,
            userId = request.userId,
        )
        val rawContent = "user=${request.userId}\ninput=${request.text}\nresponse=$response"
        val rawId = "$sessionId:${nowMs()}:raw"
        val rawTags = if (omega.value < 0.75f && delta.toList().all { kotlin.math.abs(it) <= 0.05f }) {
            setOf("daily", "stable")
        } else {
            emptySet<String>()
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
            ),
        )
        // Diary generation is consumed by the asynchronous worker after the RAW commit. The user
        // turn must never create a NARRATIVE memory synchronously or wait for Diary inference.
        return MemoryWriteOutcome(rawId, rawTrace)
    }

    companion object {
        private const val RECENT_HISTORY_LIMIT = 8

        fun create(
            personaConfig: PersonaConfig,
            llmClient: LlmClient = DevelopmentLlmStub(),
            store: SessionStateStore = MutableSessionStateStore(),
            vectorWriteService: VectorWriteService = VectorWriteService(store),
            inferenceExecutor: InferenceExecutor = DirectInferenceExecutor,
            quantizer: CodebookQuantizer = HeuristicCodebookFallback(),
            memoryEmbeddingModel: MemoryEmbeddingModel = DeterministicMemoryEmbeddingModel,
            memoryStore: MemoryStore = InMemoryMemoryPalace(inferenceExecutor, embeddingModel = memoryEmbeddingModel),
            promptBuilder: PromptBuilder = DefaultPromptBuilder(),
            diaryQueue: SessionDiaryQueue = SessionDiaryQueue(),
            diaryTaskStore: DiaryTaskStore? = null,
            diaryTriggerCoordinator: DiaryTriggerCoordinator? = null,
            traceStore: TraceStore? = null,
            centroidProvider: HomeostasisCentroidProvider = SlidingWindowHomeostasisCentroidProvider(
                memoryStore = memoryStore,
                fallback = StoredOriginCentroidProvider(store),
            ),
            userAffectAnalyzer: UserAffectAnalyzer = DeterministicUserAffectAnalyzer(),
            relationshipStore: RelationshipStateStore = InMemoryRelationshipStateStore(),
            affectInfluenceMapper: UserAffectInfluenceMapper = UserAffectInfluenceMapper.Default,
        ): DevelopmentMessagePipeline {
            return DevelopmentMessagePipeline(
                personaConfig = personaConfig,
                store = store,
                quantizer = quantizer,
                memoryRetriever = memoryStore,
                promptBuilder = promptBuilder,
                llmClient = llmClient,
                vectorWriteService = vectorWriteService,
                diaryQueue = diaryQueue,
                inferenceExecutor = inferenceExecutor,
                memoryStore = memoryStore,
                memoryEmbeddingModel = memoryEmbeddingModel,
                centroidProvider = centroidProvider,
                turnGate = SessionTurnGate(vectorWriteService.mutexRegistry),
                diaryTaskStore = diaryTaskStore,
                diaryTriggerCoordinator = diaryTriggerCoordinator,
                traceStore = traceStore,
                userAffectAnalyzer = userAffectAnalyzer,
                relationshipStore = relationshipStore,
                affectInfluenceMapper = affectInfluenceMapper,
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

private data class DiaryOutcome(
    val label: String,
    val traceTags: Set<String>,
)

private data class MemoryWriteOutcome(val rawMemoryId: String?, val traceTags: Set<String>)

private data class PipelineInferenceResult(
    val dissonance: Float,
    val quantization: QuantizationResult,
    val retrievalMode: RetrievalMode,
)
