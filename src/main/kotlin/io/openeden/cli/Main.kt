package io.openeden.cli

import io.openeden.cli.application.OpenEdenCli
import io.openeden.cli.input.CliInput
import io.openeden.cli.input.StdinCliInput
import io.openeden.cli.terminal.CliTextStreams
import io.openeden.cli.terminal.JLineTerminalSession
import io.openeden.cli.terminal.TerminalSession
import java.io.PrintWriter
import kotlin.system.exitProcess

suspend fun main(args: Array<String>) {
    val arguments = args.toList()
    val consoleInteractive = System.console() != null
    val launch: suspend () -> Int = CliIoModeSelector.select(
        arguments = arguments,
        stdinInteractive = consoleInteractive,
        stdoutInteractive = consoleInteractive,
        interactiveFactory = {
            val session = JLineTerminalSession.create()
            suspend { runInteractive(arguments, session) }
        },
        plainFactory = { terminalFailure ->
            val streams = CliTextStreams.create(
                input = System.`in`,
                output = System.out,
                error = System.err,
            )
            terminalFailure?.let { error ->
                streams.err.println(
                    "interactive terminal unavailable: ${error.message ?: error::class.simpleName}; " +
                        "using plain input",
                )
            }
            suspend { runPlain(arguments, streams) }
        },
    )
    exitProcess(launch())
}

private suspend fun runInteractive(arguments: List<String>, session: TerminalSession): Int {
    val writer = session.terminal.writer()
    return try {
        OpenEdenCli(
            input = CliInput { error("Plain stdin is unavailable while JLine owns the terminal") },
            output = writer.outputSink(),
            terminalSessionFactory = { session },
        ).run(arguments)
    } finally {
        session.close()
    }
}

private suspend fun runPlain(arguments: List<String>, streams: CliTextStreams): Int = OpenEdenCli(
    input = StdinCliInput(streams.reader),
    output = streams.out.outputSink(),
).run(arguments)

private fun PrintWriter.outputSink(): (String) -> Unit = { text ->
    print(text)
    flush()
}
