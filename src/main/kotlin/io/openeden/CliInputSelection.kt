package io.openeden

object CliInputSelection {
    fun shouldUseJLine(args: List<String>, consoleAvailable: Boolean, terminalType: String? = null): Boolean =
        args.isEmpty() && (consoleAvailable || terminalType?.startsWith("dumb", ignoreCase = true) == false)
}
