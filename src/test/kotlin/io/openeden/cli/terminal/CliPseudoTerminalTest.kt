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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliPseudoTerminalTest {
    @Test
    fun `inline scrollback and full screen chinese editing share jline cursor ownership`() {
        val gates = StreamingGates()
        val server = startServer(gates)
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
                transcriptBuffer.awaitPromptAfter(connected)

                input.write("你好\r")
                input.flush()
                val active = transcriptBuffer.awaitScreenState("complete active assistant label") { lines ->
                    lines.contains("[status] generating") && lines.contains("ATRI:")
                }
                assertTrue(active.lines.contains("ATRI:"), active.raw.boundedForFailure())
                val submittedRow = active.visibleLines.indexOf("> 你好")
                assertTrue(submittedRow >= 0, active.raw.boundedForFailure())

                gates.releaseFirstDelta.countDown()
                assertTrue(gates.firstDeltaSent.await(10, TimeUnit.SECONDS), "Server did not send first delta")
                val firstDelta = transcriptBuffer.awaitScreenState("first same-height delta") { lines ->
                    lines.contains("ATRI: 第一轮回复：你好")
                }
                assertEquals(submittedRow, firstDelta.visibleLines.indexOf("> 你好"), firstDelta.raw.boundedForFailure())

                gates.releaseCompletion.countDown()
                transcriptBuffer.awaitScreenState("first committed response") { lines ->
                    lines.contains("ATRI: 第一轮回复：你好") && lines.none(::isTransientStatus)
                }

                input.write("再见\r")
                input.flush()
                val completed = transcriptBuffer.awaitScreenState("both committed responses") { lines ->
                    lines.contains("ATRI: 第一轮回复：你好") &&
                        lines.contains("ATRI: 第二轮回复：再见") &&
                        lines.none(::isTransientStatus)
                }

                val inlineTranscript = completed.raw
                val diagnostics = inlineTranscript.boundedForFailure()
                val renderedLines = completed.lines
                val firstIndex = renderedLines.indexOf("ATRI: 第一轮回复：你好")
                val secondIndex = renderedLines.indexOf("ATRI: 第二轮回复：再见")
                assertTrue(firstIndex >= 0, diagnostics)
                assertTrue(secondIndex > firstIndex, diagnostics)
                assertEquals(1, renderedLines.count { it == "> 你好" }, diagnostics)
                assertEquals(1, renderedLines.count { it == "> 再见" }, diagnostics)
                assertFalse(renderedLines.any { it.startsWith(" [status]") }, diagnostics)
                assertFalse(renderedLines.any { it.startsWith(" ATRI:") }, diagnostics)
                assertFalse(inlineTranscript.contains('\uFFFD'), diagnostics)
                assertFalse(inlineTranscript.contains("??"), diagnostics)

                input.write("/mode full\r")
                input.flush()
                transcriptBuffer.awaitScreenState("full screen frame") { lines ->
                    val visible = lines.takeLast(30)
                    visible.firstOrNull()?.startsWith("OpenEden") == true &&
                        visible.lastOrNull() == "editor: active=false"
                }

                input.write("多字节输入")
                input.flush()
                val fullEditing = transcriptBuffer.awaitScreenState("full screen chinese input row") { lines ->
                    val visible = lines.takeLast(30)
                    visible.getOrNull(28) == "> 多字节输入"
                }
                assertEquals("> 多字节输入", fullEditing.visibleLines[28], fullEditing.raw.boundedForFailure())
                assertEquals("editor: active=false", fullEditing.visibleLines[29], fullEditing.raw.boundedForFailure())

                repeat("多字节输入".length) { input.write("\u007f") }
                input.flush()
                transcriptBuffer.awaitScreenState("empty full screen input after multibyte deletion") { lines ->
                    val visible = lines.takeLast(30)
                    visible.getOrNull(28) == ">"
                }

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

    private fun startServer(gates: StreamingGates): HttpServer {
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
                val requestNumber = requests.incrementAndGet()
                val response = if (requestNumber == 1) "第一轮回复：你好" else "第二轮回复：再见"
                exchange.responseHeaders.add("Content-Type", "text/event-stream; charset=UTF-8")
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { body ->
                    val activeEvents = """
                        event: accepted
                        data: {"requestId":"req_1"}

                        event: stage
                        data: {"stage":"generating"}
                    """.trimIndent() + "\n\n"
                    body.write(activeEvents.encodeToByteArray())
                    body.flush()
                    if (requestNumber == 1) {
                        gates.releaseFirstDelta.await(10, TimeUnit.SECONDS)
                        body.writeEvent("response.delta", """{"text":"第一轮回复：你好"}""")
                        gates.firstDeltaSent.countDown()
                        gates.releaseCompletion.await(10, TimeUnit.SECONDS)
                    } else {
                        body.writeEvent("response.delta", """{"text":"$response"}""")
                    }
                    body.writeEvent("completed", """{"requestId":"req_1","status":"completed"}""")
                }
            }
            executor = Executors.newCachedThreadPool()
            start()
        }
    }

    private fun java.io.OutputStream.writeEvent(event: String, data: String) {
        write("event: $event\ndata: $data\n\n".encodeToByteArray())
        flush()
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

    private fun StringBuffer.awaitScreenState(
        description: String,
        condition: (List<String>) -> Boolean,
    ): EmulatedSnapshot {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (true) {
            val raw = snapshot()
            val screen = ScreenTerminal(100, 30, true).apply { write(raw) }
            val lines = screen.screenAndScrollbackLines()
            if (condition(lines)) {
                val visibleLines = screen.toString().lineSequence().take(screen.rows).map(String::trimEnd).toList()
                return EmulatedSnapshot(raw, lines, visibleLines)
            }
            if (System.nanoTime() >= deadline) {
                throw AssertionError(
                    "Timed out waiting for $description:\n" +
                        "screen/history tail:\n${lines.takeLast(40).joinToString("\n")}\n" +
                        "raw tail:\n${raw.boundedForFailure()}",
                )
            }
            Thread.sleep(20)
        }
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

    private fun isTransientStatus(line: String): Boolean = line.startsWith("[status]")

    private data class EmulatedSnapshot(
        val raw: String,
        val lines: List<String>,
        val visibleLines: List<String>,
    )

    private data class StreamingGates(
        val releaseFirstDelta: CountDownLatch = CountDownLatch(1),
        val firstDeltaSent: CountDownLatch = CountDownLatch(1),
        val releaseCompletion: CountDownLatch = CountDownLatch(1),
    )

    private companion object {
        val ANSI_CSI = Regex("\\u001B\\[[0-?]*[ -/]*[@-~]")
        val PROMPT = Regex("(?:^|[\\r\\n])> ?")
    }
}
