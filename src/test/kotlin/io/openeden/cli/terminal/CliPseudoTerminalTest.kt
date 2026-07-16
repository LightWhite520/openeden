package io.openeden.cli.terminal

import com.pty4j.PtyProcessBuilder
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliPseudoTerminalTest {
    @Test
    fun `chinese input streaming and mode switching round trip through pseudo terminal`() {
        val server = startServer()
        val home = createTempDirectory("openeden-pty-home")
        writeConfig(home, server.address.port)
        val process = PtyProcessBuilder(command())
            .setDirectory(Path.of("").toAbsolutePath().toString())
            .setEnvironment(
                System.getenv().toMutableMap().apply {
                    put("TERM", "xterm-256color")
                    put("JAVA_OPTS", "-Duser.home=$home")
                },
            )
            .setInitialColumns(100)
            .setInitialRows(30)
            .setRedirectErrorStream(true)
            .start()
        val reader = Executors.newSingleThreadExecutor()
        try {
            val transcriptFuture = reader.submit<String> { process.inputReader(UTF_8).readText() }
            process.outputWriter(UTF_8).use { input ->
                input.write("你好\r")
                input.flush()
                Thread.sleep(350)
                input.write("/mode full\r")
                input.flush()
                Thread.sleep(150)
                input.write("/mode inline\r")
                input.flush()
                Thread.sleep(150)
                input.write("/exit\r")
                input.flush()
            }

            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                val transcript = transcriptFuture.get(5, TimeUnit.SECONDS)
                throw AssertionError("CLI did not exit within 20 seconds:\n$transcript")
            }
            val transcript = transcriptFuture.get(5, TimeUnit.SECONDS)

            assertEquals(0, process.exitValue(), transcript)
            assertTrue(transcript.contains("你好"), transcript)
            assertTrue(transcript.contains("回复：你好"), transcript)
            assertTrue(transcript.countOccurrences("> 你好") <= 2, transcript)
            assertFalse(transcript.contains('\uFFFD'), transcript)
            assertFalse(transcript.contains("??"), transcript)
        } finally {
            if (process.isAlive) process.destroyForcibly()
            reader.shutdownNow()
            server.stop(0)
        }
    }

    private fun command(): Array<String> {
        val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        val script = Path.of("build", "install", "openeden", "bin", if (windows) "openeden.bat" else "openeden")
            .toAbsolutePath()
            .toString()
        assertTrue(Files.isRegularFile(Path.of(script)), "Missing installed CLI: $script")
        return if (windows) arrayOf("cmd.exe", "/d", "/c", script) else arrayOf(script)
    }

    private fun writeConfig(home: Path, port: Int) {
        val directory = home.resolve(".openeden")
        Files.createDirectories(directory)
        Files.writeString(
            directory.resolve("config.json"),
            """{"serverUrl":"http://127.0.0.1:$port","userId":"local"}""",
            UTF_8,
        )
    }

    private fun startServer(): HttpServer = HttpServer.create(
        InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
        0,
    ).apply {
        createContext("/health") { exchange ->
            exchange.respond("application/json", """{"status":"ready"}""")
        }
        createContext("/api/v1/chat/stream") { exchange ->
            exchange.requestBody.use { it.readAllBytes() }
            exchange.respond(
                "text/event-stream; charset=UTF-8",
                """
                    event: accepted
                    data: {"requestId":"req_1"}

                    event: response.delta
                    data: {"text":"回复：你好"}

                    event: completed
                    data: {"requestId":"req_1","status":"completed"}

                """.trimIndent(),
            )
        }
        executor = Executors.newCachedThreadPool()
        start()
    }

    private fun HttpExchange.respond(contentType: String, body: String) {
        val bytes = body.encodeToByteArray()
        responseHeaders.add("Content-Type", contentType)
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun String.countOccurrences(value: String): Int {
        var count = 0
        var start = 0
        while (true) {
            val match = indexOf(value, start)
            if (match < 0) return count
            count += 1
            start = match + value.length
        }
    }
}
