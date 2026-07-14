package io.openeden

object CliInputSelection {
    fun shouldUseJLine(args: List<String>, consoleAvailable: Boolean): Boolean =
        args.isEmpty() && consoleAvailable
}
