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
import kotlin.time.Clock

/** Distinguishes real user turns from proactive heartbeat turns. Heartbeat turns still evolve the
 *  8D vector and evolution_index (§9.3), but MUST NOT update the user-activity silence clock. */
enum class TurnSource { USER, HEARTBEAT }

data class DevelopmentMessageRequest(
    val platform: String,
    val scopeId: String,
    val userId: String,
    val text: String,
    val emotionConfidence: Float,
    val emotionDelta: VectorDelta = VectorDelta.Zero,
    val source: TurnSource = TurnSource.USER,
)

data class DevelopmentMessageResult(
    val sessionId: String,
    val retrievalMode: RetrievalMode,
    val traceTags: Set<String>,
    val prompt: BuiltPrompt,
    val promptPreview: String,
    val response: String?,
    val updatedVector: BioVector,
    val evolutionIndex: Long,
    val diaryOutcome: String,
    val validationErrors: List<String>,
)

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
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    suspend fun handle(request: DevelopmentMessageRequest): DevelopmentMessageResult {
        val sessionId = "${request.platform}:${request.scopeId}"
        val initial = store.readOrCreate(sessionId)
        val centroid = inferenceExecutor.run { centroidProvider.centroidFor(sessionId) }
        if (centroid != initial.origin) {
            vectorWriteService.update(sessionId) { it.copy(origin = centroid) }
        }
        val current = store.read(sessionId)
        val preTick = PreTickEngine.apply(
            original = current.vector,
            signal = EmotionSignal(delta = request.emotionDelta, confidence = request.emotionConfidence),
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
        val retrievalResult = inferenceExecutor.run {
            memoryRetriever.retrieve(
                RetrievalRequest(
                    sessionId = sessionId,
                    userInput = request.text,
                    currentVector = preTick.preTicked,
                    origin = current.origin,
                    mode = inference.retrievalMode,
                ),
            )
        }
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
            ),
        )
        val llmOutput = llmClient.complete(prompt)
        val validation = LlmOutputValidator.validate(llmOutput)
        val write = if (validation.isValid && validation.delta != null) {
            val vectorWrite = vectorWriteService.applyLlmDelta(
                sessionId = sessionId,
                preTickedSnapshot = preTick.preTicked,
                delta = validation.delta,
            )
            val detectedShock = inferenceExecutor.run {
                ShockStateEngine.detectFromLlmOutput(
                    vectorDelta = validation.delta,
                    emotionConfidence = request.emotionConfidence,
                    internalLogic = validation.output?.internalLogic.orEmpty(),
                )
            }
            val shockWrite = detectedShock?.let { shock ->
                vectorWriteService.applyShock(sessionId, shock)
            }
            val applied = shockWrite?.copy(
                traceTags = vectorWrite.traceTags + shockWrite.traceTags,
            ) ?: vectorWrite
            // USER turns reset the silence clock that gates heartbeats (§9.3); heartbeat turns
            // evolve state but must not, or ATRI would silence her own proactive impulse.
            if (request.source == TurnSource.USER) {
                val refreshed = vectorWriteService.markUserActivity(sessionId, nowMs())
                applied.copy(state = refreshed)
            } else {
                applied
            }
        } else {
            VectorWriteResult(state = current, traceTags = emptySet())
        }

        val sourceTags = if (request.source == TurnSource.HEARTBEAT) setOf(TraceTag.HeartbeatSource) else emptySet()

        val diaryOutcome = diaryOutcome(sessionId, validation.delta)
        val memoryTraceTags = if (validation.isValid && validation.delta != null && validation.output != null) {
            inferenceExecutor.run {
                writeMemories(
                    request = request,
                    sessionId = sessionId,
                    preTicked = preTick.preTicked,
                    origin = current.origin,
                    omega = write.state.omega,
                    delta = validation.delta,
                    response = validation.output.response,
                    diaryOutcome = diaryOutcome,
                )
            }
        } else {
            emptySet()
        }
        val updatedOrigin = memoryStore?.let {
            inferenceExecutor.run { centroidProvider.centroidFor(sessionId) }
        }
        val centroidTags = if (updatedOrigin != null && updatedOrigin != write.state.origin) {
            vectorWriteService.update(sessionId) { it.copy(origin = updatedOrigin) }
            setOf(TraceTag.CentroidUpdated)
        } else {
            emptySet()
        }

        return DevelopmentMessageResult(
            sessionId = sessionId,
            retrievalMode = retrievalResult.mode,
            traceTags = inference.quantization.traceTags +
                retrievalResult.traceTags +
                write.traceTags +
                diaryOutcome.traceTags +
                memoryTraceTags +
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

    private fun diaryOutcome(sessionId: String, delta: VectorDelta?): DiaryOutcome =
        if (delta != null && delta.toList().any { kotlin.math.abs(it) > 0.0f }) {
            val traceTags = diaryQueue.tryEnqueue(
                DiaryEvent(
                    sessionId = sessionId,
                    traceId = "development",
                    reason = "vector_delta",
                ),
            )
            DiaryOutcome(
                label = if (traceTags.isEmpty()) "enqueued" else "overflow",
                traceTags = traceTags,
            )
        } else {
            DiaryOutcome(label = "not_triggered", traceTags = emptySet())
        }

    private suspend fun writeMemories(
        request: DevelopmentMessageRequest,
        sessionId: String,
        preTicked: BioVector,
        origin: BioVector,
        omega: OmegaState,
        delta: VectorDelta,
        response: String,
        diaryOutcome: DiaryOutcome,
    ): Set<String> {
        val store = memoryStore ?: return emptySet()
        val metadata = io.openeden.memory.MemoryMetadata(
            snapshot8D = preTicked,
            omegaState = omega.value,
            deltaVec = delta,
            snapshotOrigin = origin,
            userId = request.userId,
        )
        val rawContent = "user=${request.userId}\ninput=${request.text}\nresponse=$response"
        val rawTags = if (omega.value < 0.75f && delta.toList().all { kotlin.math.abs(it) <= 0.05f }) {
            setOf("daily", "stable")
        } else {
            emptySet()
        }
        val rawTrace = store.write(
            MemoryEntry(
                id = "$sessionId:${nowMs()}:raw",
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
        val diaryTrace = if (diaryOutcome.label == "enqueued") {
            store.write(
                MemoryEntry(
                    id = "$sessionId:${nowMs()}:diary",
                    sessionId = sessionId,
                    content = "significant_turn reason=vector_delta user=${request.userId}",
                    room = MemoryRoom.EVENT_ROOM,
                    kind = MemoryKind.DIARY,
                    semanticEmbedding = memoryEmbeddingModel.embed("significant_turn vector_delta"),
                    emotionalEmbedding = memoryEmbeddingModel.embed(preTicked),
                    metadata = metadata,
                ),
            ) + TraceTag.DiaryWritten
        } else {
            emptySet()
        }
        return rawTrace + diaryTrace
    }

    companion object {
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
            centroidProvider: HomeostasisCentroidProvider = SlidingWindowHomeostasisCentroidProvider(
                memoryStore = memoryStore,
                fallback = StoredOriginCentroidProvider(store),
            ),
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
            )
        }
    }
}

private data class DiaryOutcome(
    val label: String,
    val traceTags: Set<String>,
)

private data class PipelineInferenceResult(
    val dissonance: Float,
    val quantization: QuantizationResult,
    val retrievalMode: RetrievalMode,
)

class MutableSessionStateStore(
    private val states: MutableMap<String, SessionState> = mutableMapOf(),
) : SessionStateStore {
    override suspend fun readOrCreate(sessionId: String): SessionState =
        states.getOrPut(sessionId) { SessionStateStore.neutral(sessionId) }

    override suspend fun read(sessionId: String): SessionState = readOrCreate(sessionId)

    override suspend fun write(state: SessionState) {
        states[state.sessionId] = state
    }

    override suspend fun sessionIds(): Set<String> = states.keys.toSet()
}
