package io.openeden.runtime

import io.openeden.bio.BioVector
import io.openeden.bio.VectorDelta
import io.openeden.codebook.CodebookQuantizer
import io.openeden.llm.LlmClient
import io.openeden.llm.LlmOutputValidator
import io.openeden.memory.MemoryEmbeddingModel
import io.openeden.memory.MemoryEntry
import io.openeden.memory.MemoryKind
import io.openeden.memory.MemoryMetadata
import io.openeden.memory.MemoryRoom
import io.openeden.persona.PersonaConfig
import io.openeden.prompt.BuiltPrompt
import io.openeden.prompt.PromptSectionKeys

class LlmDiaryNarrativeGenerator(
    private val personaConfig: PersonaConfig,
    private val sessionStateStore: SessionStateStore,
    private val dataSource: DiaryDataSource,
    private val quantizer: CodebookQuantizer,
    private val inferenceExecutor: InferenceExecutor,
    private val llmClient: LlmClient,
    private val embeddingModel: MemoryEmbeddingModel,
    private val rawLimit: Int = 32,
) {
    suspend fun generate(task: DiaryTask): MemoryEntry {
        require(rawLimit > 0) { "rawLimit must be positive" }
        val state = sessionStateStore.read(task.sessionId)
        val slice = dataSource.uncoveredRawSlice(task.sessionId, task.sourceMemoryId, rawLimit)
            ?: throw IllegalArgumentException("Diary raw slice is empty")
        require(slice.memories.isNotEmpty()) { "Diary raw slice is empty" }
        val (d, quantization) = inferenceExecutor.run {
            val derivedDissonance = state.vector.derivedDissonance()
            derivedDissonance to quantizer.quantize(state.vector, derivedDissonance)
        }
        val facts = slice.memories.joinToString("\n") { "- ${it.content}" }
        val prompt = BuiltPrompt(
            systemText = buildString {
                append("You are generating a durable narrative diary. Use only the supplied Codebook definitions and facts.\n")
                append("English logical constraints: do not invent facts; preserve causal order; output the standard JSON schema; vector_delta must contain all eight keys and every value must be exactly 0.0.\n")
                append("Bio-Core definitions: ").append(quantization.semanticDefinitions.joinToString(" | ")).append('\n')
                append("Derived dissonance D: ").append(d).append('\n')
                append("Diary trigger reason: ").append(task.reason)
            },
            personaText = personaConfig.promptSections[PromptSectionKeys.DiaryNarrative]
                ?: error("Missing required persona section: ${PromptSectionKeys.DiaryNarrative}"),
            userText = "Covered RAW events:\n$facts",
        )
        val output = llmClient.complete(prompt)
        val validation = LlmOutputValidator.validate(output)
        require(validation.isValid) { "Invalid Diary LLM output: ${validation.errors.joinToString() }" }
        require(output.vectorDelta.values.all { it == 0.0f }) { "Diary vector_delta must be zero" }
        val response = output.response.trim()
        require(response.isNotEmpty()) { "Diary response must not be blank" }
        val (semantic, emotional) = inferenceExecutor.run {
            embeddingModel.embed(response) to embeddingModel.embed(state.vector)
        }
        val users = slice.memories.map { it.metadata.userId }.distinct()
        val userId = users.singleOrNull() ?: DIARY_WORKER_USER
        return MemoryEntry(
            id = "diary:${task.id}",
            sessionId = task.sessionId,
            content = response,
            room = MemoryRoom.EVENT_ROOM,
            kind = MemoryKind.NARRATIVE,
            tags = quantization.traceTags,
            semanticEmbedding = semantic,
            emotionalEmbedding = emotional,
            metadata = MemoryMetadata(state.vector, state.omega.value, VectorDelta.Zero, state.origin, userId),
        )
    }

    private companion object {
        const val DIARY_WORKER_USER = "diary-worker"
    }
}
