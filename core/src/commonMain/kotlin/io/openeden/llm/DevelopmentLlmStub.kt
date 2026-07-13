package io.openeden.llm

import io.openeden.prompt.BuiltPrompt

class DevelopmentLlmStub : LlmClient {
    override suspend fun complete(prompt: BuiltPrompt): LlmOutput = LlmOutput(
        internalLogic = "Development stub response based on injected codebook state.",
        vectorDelta = mapOf(
            "L" to 0.0f,
            "P" to 0.0f,
            "E" to 0.0f,
            "S" to 0.0f,
            "tau" to 0.0f,
            "V" to 0.0f,
            "M" to 0.0f,
            "F" to 0.0f,
        ),
        response = "OpenEden development response.",
    )
}
