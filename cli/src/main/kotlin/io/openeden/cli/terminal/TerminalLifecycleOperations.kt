package io.openeden.cli.terminal

internal interface TerminalLifecycleOperations {
    fun hasFullScreenCapabilities(): Boolean

    fun enterAlternateScreen()

    fun exitAlternateScreen()

    fun flush()

    fun restoreAttributes()

    fun closeTerminal()
}
