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
import java.util.concurrent.atomic.AtomicInteger
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
            process.outputWriter(UTF_8).use { input ->
                transcriptBuffer.awaitContains("OpenEden connected")
                input.write("你好\r")
                input.flush()
                transcriptBuffer.awaitContains("第一轮回复：你好")
                input.write("再见\r")
                input.flush()
                transcriptBuffer.awaitContains("第二轮回复：再见")
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
                transcriptFuture.get(5, TimeUnit.SECONDS)
                val transcript = transcriptBuffer.toString()
                throw AssertionError("CLI did not exit within 20 seconds:\n$transcript")
            }
            transcriptFuture.get(5, TimeUnit.SECONDS)
            val transcript = transcriptBuffer.toString()

            assertEquals(0, process.exitValue(), transcript)
            assertTrue(transcript.contains("你好"), transcript)
            assertTrue(transcript.contains("第一轮回复：你好"), transcript)
            assertTrue(transcript.contains("第二轮回复：再见"), transcript)
            val afterSecondResponse = transcript.substring(transcript.indexOf("第二轮回复：再见"))
            assertTrue(afterSecondResponse.contains("第一轮回复：你好"), transcript)
            assertTrue(transcript.countOccurrences("> 你好") <= 3, transcript)
            val physicalLines = transcript
                .replace(Regex("\\u001B\\[[0-?]*[ -/]*[@-~]"), "")
                .split("\r\n", "\n", "\r")
            assertFalse(physicalLines.any { it.startsWith(" [status]") }, transcript)
            assertFalse(physicalLines.any { it.startsWith(" ATRI:") }, transcript)
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

    private fun StringBuffer.awaitContains(value: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!contains(value)) {
            if (System.nanoTime() >= deadline) {
                throw AssertionError("Timed out waiting for '$value':\n$this")
            }
            Thread.sleep(20)
        }
    }
}
