package io.openeden.runtime.pipeline

sealed interface DevelopmentMessageEvent {
    data class Stage(val value: DevelopmentStage) : DevelopmentMessageEvent

    data class ResponseDelta(val text: String) : DevelopmentMessageEvent

    data class Completed(val result: DevelopmentMessageResult) : DevelopmentMessageEvent
}

enum class DevelopmentStage {
    PREPARING,
    GENERATING,
    FINALIZING,
}
