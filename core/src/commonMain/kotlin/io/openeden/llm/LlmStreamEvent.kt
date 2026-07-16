package io.openeden.llm

sealed interface LlmStreamEvent {
    data class ResponseDelta(val text: String) : LlmStreamEvent

    data class Completed(val output: LlmOutput) : LlmStreamEvent
}
