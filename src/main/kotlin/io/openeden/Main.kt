package io.openeden

import java.io.PrintStream
import kotlin.system.exitProcess

suspend fun main(args: Array<String>) {
    configureUtf8Console()
    exitProcess(OpenEdenCli().run(args.toList()))
}

private fun configureUtf8Console() {
    System.setOut(PrintStream(System.out, true, Charsets.UTF_8))
    System.setErr(PrintStream(System.err, true, Charsets.UTF_8))
}
