package io.openeden.llm

data class LlmOutput(
    val internalLogic: String,
    val vectorDelta: Map<String, Float>,
    val response: String,
)
