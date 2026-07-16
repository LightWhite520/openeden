package io.openeden.cli.terminal

import com.pty4j.PtyProcessBuilder
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.jline.utils.ScreenTerminal
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliPseudoTerminalTest {
    @Test
    fun `two inline chinese turns retain completed scrollback through pseudo terminal`() {
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
            val transcriptBuffer = StringBuffer()
            val transcriptFuture = reader.submit<Unit> {
                process.inputReader(UTF_8).use { output ->
                    val chunk = CharArray(1_024)
                    while (true) {
                        val read = output.read(chunk)
                        if (read < 0) break
                        transcriptBuffer.append(chunk, 0, read)
                    }
                }
            }
            val input = process.outputWriter(UTF_8)
            try {
                val connected = transcriptBuffer.awaitMarkerAfter(0, "OpenEden connected")
                val initiallyReady = transcriptBuffer.awaitPromptAfter(connected)

                input.write("你好\r")
                input.flush()
                val firstResponse = transcriptBuffer.awaitMarkerAfter(initiallyReady, "第一轮回复：你好")
                val readyAfterFirst = transcriptBuffer.awaitPromptAfter(firstResponse)

                input.write("再见\r")
                input.flush()
                val secondResponse = transcriptBuffer.awaitMarkerAfter(readyAfterFirst, "第二轮回复：再见")
                transcriptBuffer.awaitPromptAfter(secondResponse)

                val inlineTranscript = transcriptBuffer.snapshot()
                val diagnostics = inlineTranscript.boundedForFailure()
                val renderedLines = ScreenTerminal(100, 30, true)
                    .apply { write(inlineTranscript) }
                    .screenAndScrollbackLines()
                val firstIndex = renderedLines.indexOf("ATRI: 第一轮回复：你好")
                val secondIndex = renderedLines.indexOf("ATRI: 第二轮回复：再见")
                assertTrue(firstIndex >= 0, diagnostics)
                assertTrue(secondIndex > firstIndex, diagnostics)
                assertFalse(renderedLines.any { it.startsWith(" [status]") }, diagnostics)
                assertFalse(renderedLines.any { it.startsWith(" ATRI:") }, diagnostics)
                assertFalse(inlineTranscript.contains('\uFFFD'), diagnostics)
                assertFalse(inlineTranscript.contains("??"), diagnostics)

                input.write("/exit\r")
                input.flush()

                if (!process.waitFor(20, TimeUnit.SECONDS)) {
                    throw AssertionError("CLI did not exit within 20 seconds:\n${transcriptBuffer.boundedForFailure()}")
                }
                transcriptFuture.get(5, TimeUnit.SECONDS)
                assertEquals(0, process.exitValue(), transcriptBuffer.boundedForFailure())
            } finally {
                input.close()
            }
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

    private fun startServer(): HttpServer {
        val requests = AtomicInteger()
        return HttpServer.create(
            InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
            0,
        ).apply {
            createContext("/health") { exchange ->
                exchange.respond("application/json", """{"status":"ready"}""")
            }
            createContext("/api/v1/chat/stream") { exchange ->
                exchange.requestBody.use { it.readAllBytes() }
                val response = if (requests.incrementAndGet() == 1) "第一轮回复：你好" else "第二轮回复：再见"
                exchange.respond(
                    "text/event-stream; charset=UTF-8",
                    """
                        event: accepted
                        data: {"requestId":"req_1"}

                        event: response.delta
                        data: {"text":"$response"}

                        event: completed
                        data: {"requestId":"req_1","status":"completed"}

                    """.trimIndent(),
                )
            }
            executor = Executors.newCachedThreadPool()
            start()
        }
    }

    private fun HttpExchange.respond(contentType: String, body: String) {
        val bytes = body.encodeToByteArray()
        responseHeaders.add("Content-Type", contentType)
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun StringBuffer.awaitMarkerAfter(offset: Int, marker: String): Int = awaitOutput(
        description = "'$marker' after offset $offset",
    ) { output ->
        output.indexOf(marker, offset).takeIf { it >= 0 }?.plus(marker.length)
    }

    private fun StringBuffer.awaitPromptAfter(offset: Int): Int = awaitOutput(
        description = "a new prompt after offset $offset",
    ) { output ->
        val suffix = output.substring(offset).stripAnsi()
        output.length.takeIf { PROMPT.containsMatchIn(suffix) }
    }

    private fun <T> StringBuffer.awaitOutput(description: String, condition: (String) -> T?): T {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (true) {
            val output = snapshot()
            condition(output)?.let { return it }
            if (System.nanoTime() >= deadline) {
                throw AssertionError("Timed out waiting for $description:\n${output.boundedForFailure()}")
            }
            Thread.sleep(20)
        }
    }

    private fun StringBuffer.snapshot(): String = synchronized(this) { toString() }

    private fun StringBuffer.boundedForFailure(): String = snapshot().boundedForFailure()

    private fun String.boundedForFailure(): String = takeLast(4_000)

    private fun String.stripAnsi(): String = replace(ANSI_CSI, "")

    private companion object {
        val ANSI_CSI = Regex("\\u001B\\[[0-?]*[ -/]*[@-~]")
        val PROMPT = Regex("(?:^|[\\r\\n])> ?")
    }
}
