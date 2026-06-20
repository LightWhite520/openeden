package io.openeden.runtime

import io.openeden.bio.BioVector
import io.openeden.bio.VectorDelta
import io.openeden.bio.VectorMapping
import io.openeden.codebook.CodebookQuantizer
import io.openeden.codebook.HeuristicCodebookFallback
import io.openeden.llm.DevelopmentLlmStub
import io.openeden.llm.LlmClient
import io.openeden.llm.LlmOutputValidator
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

data class DevelopmentMessageRequest(
    val platform: String,
    val scopeId: String,
    val userId: String,
    val text: String,
    val emotionConfidence: Float,
    val emotionDelta: VectorDelta = VectorDelta.Zero,
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
    private val store: MutableSessionStateStore,
    private val quantizer: CodebookQuantizer,
    private val memoryRetriever: MemoryRetriever,
    private val promptBuilder: PromptBuilder,
    private val llmClient: LlmClient,
    private val vectorWriteService: VectorWriteService,
    private val diaryQueue: SessionDiaryQueue,
) {
    suspend fun handle(request: DevelopmentMessageRequest): DevelopmentMessageResult {
        val sessionId = "${request.platform}:${request.scopeId}"
        val current = store.readOrCreate(sessionId)
        val preTick = PreTickEngine.apply(
            original = current.vector,
            signal = EmotionSignal(delta = request.emotionDelta, confidence = request.emotionConfidence),
        )
        val dissonance = preTick.preTicked.derivedDissonance()
        val quantization = quantizer.quantize(preTick.preTicked, dissonance)
        val internalVector = VectorMapping.toInternal(preTick.preTicked, current.origin)
        val retrievalMode = RetrievalModeSelector.select(
            internalVector = internalVector,
            omegaState = current.omega,
            shockState = current.shockState,
        )
        val retrievalResult = memoryRetriever.retrieve(
            RetrievalRequest(
                sessionId = sessionId,
                userInput = request.text,
                currentVector = preTick.preTicked,
                origin = current.origin,
                mode = retrievalMode,
            ),
        )
        val prompt = promptBuilder.build(
            PromptInput(
                personaConfig = personaConfig,
                evolutionIndex = current.evolutionIndex,
                vectorSnapshot = preTick.preTicked,
                derivedDissonance = dissonance,
                quantization = quantization,
                retrievalResult = retrievalResult,
                omegaState = current.omega,
                shockState = current.shockState,
                userInput = request.text,
            ),
        )
        val llmOutput = llmClient.complete(prompt)
        val validation = LlmOutputValidator.validate(llmOutput)
        val write = if (validation.isValid && validation.delta != null) {
            vectorWriteService.applyLlmDelta(
                sessionId = sessionId,
                preTickedSnapshot = preTick.preTicked,
                delta = validation.delta,
            )
        } else {
            VectorWriteResult(state = current, traceTags = emptySet())
        }

        val diaryOutcome = diaryOutcome(sessionId, validation.delta)

        return DevelopmentMessageResult(
            sessionId = sessionId,
            retrievalMode = retrievalResult.mode,
            traceTags = quantization.traceTags + write.traceTags + diaryOutcome.traceTags,
            prompt = prompt,
            promptPreview = listOf(prompt.systemText, prompt.personaText, prompt.userText).joinToString("\n\n"),
            response = validation.output?.response,
            updatedVector = write.state.vector,
            evolutionIndex = write.state.evolutionIndex,
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

    companion object {
        fun create(
            personaConfig: PersonaConfig,
            llmClient: LlmClient = DevelopmentLlmStub(),
        ): DevelopmentMessagePipeline {
            val store = MutableSessionStateStore()
            return DevelopmentMessagePipeline(
                personaConfig = personaConfig,
                store = store,
                quantizer = HeuristicCodebookFallback(),
                memoryRetriever = EmptyMemoryRetriever,
                promptBuilder = DefaultPromptBuilder(),
                llmClient = llmClient,
                vectorWriteService = VectorWriteService(store),
                diaryQueue = SessionDiaryQueue(),
            )
        }
    }
}

private data class DiaryOutcome(
    val label: String,
    val traceTags: Set<String>,
)

class MutableSessionStateStore(
    private val states: MutableMap<String, SessionState> = mutableMapOf(),
) : SessionStateStore {
    suspend fun readOrCreate(sessionId: String): SessionState =
        states.getOrPut(sessionId) {
            SessionState(
                sessionId = sessionId,
                vector = BioVector.Neutral,
                origin = BioVector.Neutral,
                omega = OmegaState(0.0f),
                shockState = null,
                evolutionIndex = 0,
            )
        }

    override suspend fun read(sessionId: String): SessionState = readOrCreate(sessionId)

    override suspend fun write(state: SessionState) {
        states[state.sessionId] = state
    }
}

private object EmptyMemoryRetriever : MemoryRetriever {
    override suspend fun retrieve(request: RetrievalRequest): RetrievalResult =
        RetrievalResult(
            mode = request.mode,
            injectionLabel = RetrievalModeSelector.injectionLabel(request.mode),
            memories = emptyList(),
        )
}
