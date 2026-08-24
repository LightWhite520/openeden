package io.openeden.transcript

data class PromptHistoryChunk(
    val sessionId: String,
    val cacheEpoch: Long,
    val firstTurnId: String,
    val lastTurnId: String,
    val turnIds: List<String>,
    val serializedText: String,
    val tokenCount: Int,
    val fingerprint: String,
    val serializerVersion: Int,
)
