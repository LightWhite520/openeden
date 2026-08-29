package io.openeden.transcript

import kotlinx.serialization.Serializable

@Serializable
data class PromptHistoryChunk(
    val sessionId: String,
    val cacheEpoch: Long,
    val items: List<PromptHistoryItem>,
    val tokenCount: Int,
    val serializerVersion: Int,
) {
    init {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(cacheEpoch >= 0L) { "cacheEpoch must not be negative" }
        require(items.isNotEmpty()) { "items must not be empty" }
        require(tokenCount >= 0) { "tokenCount must not be negative" }
        require(serializerVersion > 0) { "serializerVersion must be positive" }
    }

    val turnIds: List<String>
        get() = items.map(PromptHistoryItem::turnId).distinct()

    val firstTurnId: String
        get() = items.first().turnId

    val lastTurnId: String
        get() = items.last().turnId

    val fingerprint: String
        get() = PromptHistorySerializer.fingerprintItems(items)
}
