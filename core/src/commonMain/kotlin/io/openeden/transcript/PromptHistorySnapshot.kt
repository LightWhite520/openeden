package io.openeden.transcript

import kotlinx.serialization.Serializable

@Serializable
data class PromptHistorySnapshot(
    val stableChunks: List<PromptHistoryChunk> = emptyList(),
    val summary: PromptHistorySummary? = null,
    val mutableTail: List<PromptHistoryItem> = emptyList(),
    val sourceTurnIds: Set<String> = emptySet(),
    val cacheEpoch: Long = 0L,
) {
    init {
        require(sourceTurnIds.none(String::isBlank)) { "sourceTurnIds must not contain blanks" }
        require(cacheEpoch >= 0L) { "cacheEpoch must not be negative" }
    }

    fun flattenItems(): List<PromptHistoryItem> = buildList {
        stableChunks.forEach { addAll(it.items) }
        addAll(mutableTail)
    }
}
