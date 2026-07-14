package io.openeden.cli.command

import io.openeden.cli.state.CliMode

sealed interface CliCommand {
    data object Help : CliCommand

    data object State : CliCommand

    data class Mode(val mode: CliMode) : CliCommand

    data class Inspect(val visible: Boolean) : CliCommand

    data object Clear : CliCommand

    data object Exit : CliCommand

    data class Unknown(val name: String) : CliCommand
}
