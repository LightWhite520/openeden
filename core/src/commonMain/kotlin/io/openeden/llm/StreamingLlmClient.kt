package io.openeden.llm

import io.openeden.prompt.BuiltPrompt
import kotlinx.coroutines.flow.Flow

interface StreamingLlmClient : LlmClient {
    val supportsStrictStructuredStreaming: Boolean

    fun stream(prompt: BuiltPrompt): Flow<LlmStreamEvent>

    fun stream(prompt: BuiltPrompt, generationSettings: LlmGenerationSettings): Flow<LlmStreamEvent> = stream(prompt)
}
