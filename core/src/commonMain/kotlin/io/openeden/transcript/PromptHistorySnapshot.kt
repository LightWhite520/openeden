package io.openeden.transcript

data class PromptHistorySnapshot(
    val stableChunks: List<PromptHistoryChunk> = emptyList(),
    val frozenSummary: String? = null,
    val mutableTail: List<ConversationTurn> = emptyList(),
    val sourceTurnIds: Set<String> = emptySet(),
    val cacheEpoch: Long = 0L,
)
