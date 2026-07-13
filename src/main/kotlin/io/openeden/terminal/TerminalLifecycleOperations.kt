package io.openeden.terminal

internal interface TerminalLifecycleOperations {
    fun hasFullScreenCapabilities(): Boolean

    fun enterAlternateScreen()

    fun hideCursor()

    fun showCursor()

    fun exitAlternateScreen()

    fun flush()

    fun restoreAttributes()

    fun closeTerminal()
}
