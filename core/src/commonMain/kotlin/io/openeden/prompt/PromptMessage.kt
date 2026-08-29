package io.openeden.prompt

data class PromptMessage(
    val role: PromptRole,
    val content: String,
    val segmentKind: PromptSegmentKind,
)
