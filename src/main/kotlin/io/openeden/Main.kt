package io.openeden

import io.openeden.terminal.CliTextStreams
import io.openeden.terminal.TerminalEncodingProfile
import kotlin.system.exitProcess

suspend fun main(args: Array<String>) {
    val streams = CliTextStreams.create(
        input = System.`in`,
        output = System.out,
        error = System.err,
        profile = TerminalEncodingProfile.fromEnvironment(System.getenv()),
    )
    val arguments = args.toList()
    val terminalSession = if (CliInputSelection.shouldUseJLine(arguments, System.console() != null)) {
        runCatching { io.openeden.terminal.JLineTerminalSession.create() }.getOrNull()
    } else {
        null
    }
    val output: (String) -> Unit = if (terminalSession != null) {
        { text ->
            terminalSession.terminal.writer().print(text)
            terminalSession.terminal.writer().flush()
        }
    } else {
        { text ->
            streams.out.print(text)
            streams.out.flush()
        }
    }
    val cli = OpenEdenCli(
        input = terminalSession?.let { JLineCliInput(it.lineReader) } ?: StdinCliInput(streams.reader),
        output = output,
    )
    val exitCode = try {
        cli.run(arguments)
    } finally {
        terminalSession?.close()
    }
    exitProcess(exitCode)
}
