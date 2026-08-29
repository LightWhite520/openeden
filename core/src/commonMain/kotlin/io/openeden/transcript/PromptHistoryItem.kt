package io.openeden.transcript

import kotlinx.serialization.Serializable

@Serializable
data class PromptHistoryItem(
    val role: String,
    val text: String,
    val turnId: String,
    val fingerprint: String,
) {
    init {
        require(role.isNotBlank()) { "role must not be blank" }
        require(turnId.isNotBlank()) { "turnId must not be blank" }
        require(fingerprint.isNotBlank()) { "fingerprint must not be blank" }
    }
}
