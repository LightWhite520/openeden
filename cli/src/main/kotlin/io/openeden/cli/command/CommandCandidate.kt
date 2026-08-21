package io.openeden.cli.command

data class CommandCandidate(
    val value: String,
    val description: String,
    val shortcut: String? = null,
)
