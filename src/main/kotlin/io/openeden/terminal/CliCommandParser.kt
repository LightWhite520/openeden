package io.openeden.terminal

data class CommandCandidate(
    val value: String,
    val description: String,
    val shortcut: String? = null,
)

class CliCommandParser {
    fun parse(input: String): CliCommand {
        val trimmed = input.trim()
        val tokens = if (trimmed.isEmpty()) emptyList() else trimmed.split(WHITESPACE)
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

        val separatorIndex = input.indexOfFirst(Char::isWhitespace)
        if (separatorIndex < 0) {
            return ROOT_CANDIDATES.filter { candidate -> candidate.value.startsWith(input) }
        }

        val command = input.substring(0, separatorIndex)
        val argument = input.substring(separatorIndex).trimStart()
        if (argument.any(Char::isWhitespace)) return emptyList()

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

    private companion object {
        val WHITESPACE = Regex("\\s+")
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
