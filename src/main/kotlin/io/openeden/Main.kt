package io.openeden

import io.openeden.terminal.CliTextStreams
import io.openeden.terminal.JLineTerminalSession
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
