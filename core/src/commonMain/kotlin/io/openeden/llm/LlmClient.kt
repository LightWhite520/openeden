package io.openeden.llm

import io.openeden.prompt.BuiltPrompt

interface LlmClient {
    suspend fun complete(prompt: BuiltPrompt): LlmOutput
}
