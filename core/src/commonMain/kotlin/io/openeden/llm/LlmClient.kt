package io.openeden.llm

import io.openeden.prompt.BuiltPrompt

interface LlmClient {
    suspend fun complete(prompt: BuiltPrompt): LlmOutput

    suspend fun complete(prompt: BuiltPrompt, generationSettings: LlmGenerationSettings): LlmOutput = complete(prompt)
}
