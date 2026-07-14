package io.openeden.cli.command

import io.openeden.cli.render.Size
import io.openeden.cli.state.CliMode

class CliCommandParser {
    fun parse(input: String): CliCommand {
        val trimmed = input.trim { char -> char.isCommandWhitespace() }
        val tokens = tokenize(trimmed)
        val name = tokens.firstOrNull().orEmpty()

        return when (name) {
            "/help" -> withoutArguments(tokens, name, CliCommand.Help)
            "/state" -> withoutArguments(tokens, name, CliCommand.State)
            "/clear" -> withoutArguments(tokens, name, CliCommand.Clear)
            "/exit" -> withoutArguments(tokens, name, CliCommand.Exit)
            "/mode" -> parseMode(tokens)
            "/inspect" -> parseInspect(tokens)
            else -> CliCommand.Unknown(name)
        }
    }

    fun complete(input: String): List<CommandCandidate> {
        if (!input.startsWith('/')) return emptyList()

        val separatorIndex = input.indexOfFirst { char -> char.isCommandWhitespace() }
        if (separatorIndex < 0) {
            return ROOT_CANDIDATES.filter { candidate -> candidate.value.startsWith(input) }
        }

        val command = input.substring(0, separatorIndex)
        val argument = input.substring(separatorIndex).trimStart { char -> char.isCommandWhitespace() }
        if (argument.any { char -> char.isCommandWhitespace() }) return emptyList()

        val candidates = ARGUMENT_CANDIDATES[command] ?: return emptyList()
        if (candidates.any { candidate -> candidate.value == argument }) return emptyList()
        return candidates.filter { candidate -> candidate.value.startsWith(argument) }
    }

    private fun parseMode(tokens: List<String>): CliCommand {
        require(tokens.size == 2) { MODE_USAGE }
        val mode = when (tokens[1]) {
            "inline" -> CliMode.INLINE
            "full" -> CliMode.FULL_SCREEN
            else -> throw IllegalArgumentException(MODE_USAGE)
        }
        return CliCommand.Mode(mode)
    }

    private fun parseInspect(tokens: List<String>): CliCommand {
        require(tokens.size == 2) { INSPECT_USAGE }
        val visible = when (tokens[1]) {
            "on" -> true
            "off" -> false
            else -> throw IllegalArgumentException(INSPECT_USAGE)
        }
        return CliCommand.Inspect(visible)
    }

    private fun withoutArguments(
        tokens: List<String>,
        name: String,
        command: CliCommand,
    ): CliCommand {
        require(tokens.size == 1) { "Usage: $name" }
        return command
    }

    private fun tokenize(input: String): List<String> {
        if (input.isEmpty()) return emptyList()

        val tokens = mutableListOf<String>()
        var tokenStart = 0
        input.forEachIndexed { index, char ->
            if (char.isCommandWhitespace()) {
                if (tokenStart < index) tokens += input.substring(tokenStart, index)
                tokenStart = index + 1
            }
        }
        if (tokenStart < input.length) tokens += input.substring(tokenStart)
        return tokens
    }

    private companion object {
        const val MODE_USAGE = "Usage: /mode inline|full"
        const val INSPECT_USAGE = "Usage: /inspect on|off"

        val ROOT_CANDIDATES = listOf(
            CommandCandidate("/help", "Show available commands"),
            CommandCandidate("/state", "Show the current session state"),
            CommandCandidate("/mode", "Select the terminal display mode"),
            CommandCandidate("/inspect", "Show or hide diagnostics"),
            CommandCandidate("/clear", "Clear visible conversation history"),
            CommandCandidate("/exit", "Exit the terminal client"),
        )

        val ARGUMENT_CANDIDATES = mapOf(
            "/mode" to listOf(
                CommandCandidate("full", "Use the full-screen display mode"),
                CommandCandidate("inline", "Use the inline display mode"),
            ),
            "/inspect" to listOf(
                CommandCandidate("on", "Show diagnostics"),
                CommandCandidate("off", "Hide diagnostics"),
            ),
        )
    }
}

private fun Char.isCommandWhitespace(): Boolean = isWhitespace()
