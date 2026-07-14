package io.openeden.cli.state

enum class CliRole {
    USER,
    ASSISTANT,
    SYSTEM,
}

enum class CliMessageStatus {
    COMPLETE,
    STREAMING,
    INTERRUPTED,
    FAILED,
}

data class CliMessage(
    val id: String,
    val role: CliRole,
    val markdown: String,
    val status: CliMessageStatus,
) {
    val provisional: Boolean
        get() = status == CliMessageStatus.STREAMING
}
