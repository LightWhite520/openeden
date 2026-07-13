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
    val cli = OpenEdenCli(
        input = StdinCliInput(streams.reader),
        output = { text ->
            streams.out.print(text)
            streams.out.flush()
        },
    )
    exitProcess(cli.run(args.toList()))
}
