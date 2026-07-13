package io.openeden.terminal

internal interface TerminalLifecycleOperations {
    fun hasFullScreenCapabilities(): Boolean

    fun enterFullScreen()

    fun exitFullScreen()

    fun restoreAttributes()

    fun closeTerminal()
}
