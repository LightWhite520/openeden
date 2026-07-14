package io.openeden.cli

import io.openeden.cli.application.OpenEdenCli
import io.openeden.cli.input.JLineCliInput
import io.openeden.cli.input.StdinCliInput
import io.openeden.cli.terminal.CliTextStreams
import io.openeden.cli.terminal.JLineTerminalSession
import io.openeden.cli.terminal.TerminalEncodingProfile
import io.openeden.cli.terminal.TerminalSession
import kotlin.system.exitProcess

suspend fun main(args: Array<String>) {
    val streams = CliTextStreams.create(
        input = System.`in`,
        output = System.out,
        error = System.err,
        profile = TerminalEncodingProfile.fromEnvironment(System.getenv()),
    )
    val arguments = args.toList()
    val terminalSession = if (arguments.isEmpty()) {
        runCatching { JLineTerminalSession.create() }
            .getOrElse { failure ->
                throw IllegalStateException(
                    "Interactive terminal takeover is unavailable: ${failure.message ?: failure::class.simpleName}",
                    failure,
                )
            }
    } else null
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
    val exitCode = terminalSession.use { _ -> cli.run(arguments) }
    exitProcess(exitCode)
}
