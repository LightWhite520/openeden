package io.openeden.terminal

data class CommandCandidate(
    val value: String,
    val description: String,
    val shortcut: String? = null,
)
