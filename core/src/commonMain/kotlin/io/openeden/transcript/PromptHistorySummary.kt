package io.openeden.transcript

import kotlinx.serialization.Serializable

@Serializable
data class PromptHistorySummary(
    val text: String,
    val sourceTurnIds: Set<String>,
    val fingerprint: String,
    val serializerVersion: Int,
) {
    init {
        require(text.isNotBlank()) { "text must not be blank" }
        require(sourceTurnIds.none(String::isBlank)) { "sourceTurnIds must not contain blanks" }
        require(fingerprint.isNotBlank()) { "fingerprint must not be blank" }
        require(serializerVersion > 0) { "serializerVersion must be positive" }
    }
}
