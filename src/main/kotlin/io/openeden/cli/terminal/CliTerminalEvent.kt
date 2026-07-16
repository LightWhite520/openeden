package io.openeden.cli.terminal

sealed interface CliTerminalEvent {
    data class Submit(val text: String) : CliTerminalEvent

    data object Cancel : CliTerminalEvent

    data object ToggleMode : CliTerminalEvent

    data object ToggleDiagnostics : CliTerminalEvent

    data object LoadOlderHistory : CliTerminalEvent

    data object EndOfFile : CliTerminalEvent
}
