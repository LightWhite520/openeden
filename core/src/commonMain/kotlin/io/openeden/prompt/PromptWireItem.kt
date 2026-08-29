package io.openeden.prompt

data class PromptWireItem(
    val role: PromptRole,
    val text: String,
    val turnIds: List<String>,
    val fingerprint: String,
) {
    init {
        require(turnIds.none(String::isBlank)) { "turnIds must not contain blanks" }
        require(fingerprint.isNotBlank()) { "fingerprint must not be blank" }
    }
}
