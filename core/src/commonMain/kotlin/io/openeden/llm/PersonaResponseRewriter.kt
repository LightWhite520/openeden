package io.openeden.llm

import io.openeden.persona.PersonaOutputPolicy

fun interface PersonaResponseRewriter {
    suspend fun rewriteResponseOnly(
        output: LlmOutput,
        policy: PersonaOutputPolicy,
    ): LlmOutput

    suspend fun rewriteIfNeeded(
        output: LlmOutput,
        policy: PersonaOutputPolicy,
        recentAssistantResponses: List<String> = emptyList(),
    ): LlmOutput {
        if (!LlmOutputValidator.validate(output).isValid) return output
        if (LlmOutputValidator.validate(output, policy, recentAssistantResponses).isValid) return output

        val rewritten = rewriteResponseOnly(output, policy)
        return output.copy(response = rewritten.response)
    }
}
