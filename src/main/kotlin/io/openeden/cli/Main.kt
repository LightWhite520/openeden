package io.openeden.cli

import io.openeden.cli.application.OpenEdenCli
import io.openeden.cli.input.StdinCliInput
import io.openeden.cli.terminal.CliTextStreams
import io.openeden.cli.terminal.JLineTerminalSession
import io.openeden.cli.terminal.TerminalEncodingProfile
import kotlin.system.exitProcess

suspend fun main(args: Array<String>) {
    val streams = CliTextStreams.create(
        input = System.`in`,
        output = System.out,
        error = System.err,
        profile = TerminalEncodingProfile.fromEnvironment(System.getenv()),
    )
    val arguments = args.toList()
    val interactive = arguments.isEmpty() && System.console() != null
    val output: (String) -> Unit = { text ->
        streams.out.print(text)
        streams.out.flush()
    }
    val cli = OpenEdenCli(
        input = StdinCliInput(streams.reader),
        output = output,
        terminalSessionFactory = if (interactive) {
            { JLineTerminalSession.create() }
        } else {
            null
        },
    )
    val exitCode = cli.run(arguments)
    exitProcess(exitCode)
}
