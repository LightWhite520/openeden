package io.openeden.prompt

interface PromptBuilder {
    suspend fun build(input: PromptInput): BuiltPrompt
}
