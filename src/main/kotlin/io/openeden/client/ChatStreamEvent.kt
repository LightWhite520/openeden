package io.openeden.client

sealed interface ChatStreamEvent {
    data class Accepted(val requestId: String) : ChatStreamEvent

    data class Stage(val stage: String) : ChatStreamEvent

    data class ResponseDelta(val text: String) : ChatStreamEvent

    data class Completed(val requestId: String, val status: String) : ChatStreamEvent

    data class Error(val code: String, val message: String, val retryable: Boolean) : ChatStreamEvent
}
