package io.openeden.runtime.pipeline

import io.openeden.runtime.affect.OmegaState
import io.openeden.runtime.affect.ShockState
import io.openeden.runtime.diary.DiaryTaskStore
import io.openeden.runtime.inference.DirectInferenceExecutor
import io.openeden.runtime.inference.InferenceExecutor
import io.openeden.runtime.session.MutableSessionStateStore
import io.openeden.runtime.session.SessionStateStore
import io.openeden.runtime.state.VectorWriteService

import io.openeden.bio.BioVector
import io.openeden.bio.VectorDelta
import io.openeden.codebook.CodebookQuantizer
import io.openeden.codebook.HeuristicCodebookFallback
import io.openeden.llm.LlmClient
import io.openeden.memory.InMemoryMemoryPalace
import io.openeden.memory.DeterministicMemoryEmbeddingModel
import io.openeden.memory.MemoryEmbeddingModel
import io.openeden.memory.MemoryStore
import io.openeden.memory.RetrievalMode
import io.openeden.persona.PersonaConfig
import io.openeden.prompt.BuiltPrompt

data class LocalRuntimeRequest(
    val turnId: String,
    val userId: String,
    val text: String,
    val emotionConfidence: Float = 0.0f,
    val emotionDelta: VectorDelta = VectorDelta.Zero,
)

data class LocalRuntimeResult(
    val sessionId: String,
    val retrievalMode: RetrievalMode,
    val traceTags: Set<String>,
    val prompt: BuiltPrompt,
    val response: String?,
    val updatedVector: BioVector,
    val evolutionIndex: Long,
    val omega: OmegaState,
    val shockState: ShockState?,
    val validationErrors: List<String>,
)

class OpenEdenRuntimePipeline private constructor(
    private val delegate: DevelopmentMessagePipeline,
    private val store: SessionStateStore,
) {
    suspend fun handle(request: LocalRuntimeRequest): LocalRuntimeResult {
        val result = delegate.handle(
            DevelopmentMessageRequest(
                turnId = request.turnId,
                platform = LOCAL_PLATFORM,
                scopeId = request.userId,
                userId = request.userId,
                text = request.text,
                emotionConfidence = request.emotionConfidence,
                emotionDelta = request.emotionDelta,
            ),
        )
        val state = store.read(result.sessionId)
        return LocalRuntimeResult(
            sessionId = result.sessionId,
            retrievalMode = result.retrievalMode,
            traceTags = result.traceTags,
            prompt = result.prompt,
            response = result.response,
            updatedVector = result.updatedVector,
            evolutionIndex = result.evolutionIndex,
            omega = state.omega,
            shockState = state.shockState,
            validationErrors = result.validationErrors,
        )
    }

    companion object {
        const val LOCAL_PLATFORM = "CLI"

        fun local(
            personaConfig: PersonaConfig,
            llmClient: LlmClient,
            store: SessionStateStore = MutableSessionStateStore(),
            vectorWriteService: VectorWriteService = VectorWriteService(store),
            inferenceExecutor: InferenceExecutor = DirectInferenceExecutor,
            quantizer: CodebookQuantizer = HeuristicCodebookFallback(),
            memoryEmbeddingModel: MemoryEmbeddingModel = DeterministicMemoryEmbeddingModel,
            memoryStore: MemoryStore = InMemoryMemoryPalace(inferenceExecutor, embeddingModel = memoryEmbeddingModel),
            diaryTaskStore: DiaryTaskStore? = null,
            traceStore: io.openeden.trace.TraceStore? = null,
        ): OpenEdenRuntimePipeline {
            val pipeline = DevelopmentMessagePipeline.create(
                personaConfig = personaConfig,
                llmClient = llmClient,
                store = store,
                vectorWriteService = vectorWriteService,
                inferenceExecutor = inferenceExecutor,
                quantizer = quantizer,
                memoryStore = memoryStore,
                memoryEmbeddingModel = memoryEmbeddingModel,
                diaryTaskStore = diaryTaskStore,
                traceStore = traceStore,
            )
            return OpenEdenRuntimePipeline(pipeline, store)
        }
    }
}
