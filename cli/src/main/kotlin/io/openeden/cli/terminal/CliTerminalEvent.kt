package io.openeden.cli.terminal

sealed interface CliTerminalEvent {
    data class Submit(
        val text: String,
        val inlineTerminalCommitted: Boolean = false,
    ) : CliTerminalEvent

    data object Cancel : CliTerminalEvent

    data object ToggleMode : CliTerminalEvent

    data object ToggleDiagnostics : CliTerminalEvent

    data object LoadOlderHistory : CliTerminalEvent

    data class Resized(val columns: Int, val rows: Int) : CliTerminalEvent

    data object EndOfFile : CliTerminalEvent
}
