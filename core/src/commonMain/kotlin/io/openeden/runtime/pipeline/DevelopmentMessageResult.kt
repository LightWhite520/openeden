package io.openeden.runtime.pipeline

import io.openeden.bio.BioVector
import io.openeden.memory.RetrievalMode
import io.openeden.prompt.BuiltPrompt

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
