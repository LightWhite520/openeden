package io.openeden.cli.terminal

import io.openeden.cli.render.Size

import com.pty4j.PtyProcessBuilder
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WindowsTerminalInputE2ETest {
    @Test
    fun `Chinese text can be inserted at the cursor under code page 936`() {
        if (!enabled()) return

        val result = runInPowerShell(
            input = "\u4E2D\u6587AB\u001B[D\u001B[D\u6D4B\u8BD5\r",
        )

        assertEquals("\u4E2D\u6587\u6D4B\u8BD5AB", result.submitted)
        assertContains(result.output, "\u56DE\u590D\u6B63\u5E38")
    }

    @Test
    fun `backspace removes an entire supplementary Unicode character`() {
        if (!enabled()) return

        val result = runInPowerShell(
            input = "\u4E2D\u6587\uD83D\uDE00\b\r",
        )

        assertEquals("\u4E2D\u6587", result.submitted)
        assertContains(result.output, "\u56DE\u590D\u6B63\u5E38")
    }

    private fun runInPowerShell(input: String): TerminalResult {
        val submitted = ArrayBlockingQueue<String>(1)
        val server = startServer(submitted)
        val home = Files.createTempDirectory("openeden-terminal-e2e")
        val configDir = Files.createDirectories(home.resolve(".openeden"))
        Files.writeString(
            configDir.resolve("config.json"),
            """{"serverUrl":"http://127.0.0.1:${server.address.port}","userId":"terminal-test"}""",
        )
        val javaHome = System.getenv("JAVA_HOME") ?: System.getProperty("java.home")
        val environment = HashMap(System.getenv()).apply {
            put("JAVA_HOME", javaHome)
            put("OPENEDEN_OPTS", "-Duser.home=\"$home\"")
        }
        val repository = Path.of("").toAbsolutePath().normalize()
        val command = "chcp 936 | Out-Null; & '.\\build\\install\\openeden\\bin\\openeden.bat'"
        val process = PtyProcessBuilder(arrayOf("pwsh.exe", "-NoLogo", "-NoProfile", "-Command", command))
            .setDirectory(repository.toString())
            .setEnvironment(environment)
            .setUseWinConPty(true)
            .setInitialColumns(120)
            .setInitialRows(40)
            .start()
        val captured = ByteArrayOutputStream()
        val reader = thread(isDaemon = true, name = "openeden-terminal-e2e-reader") {
            process.inputStream.copyTo(captured)
        }

        try {
            assertTrue(awaitOutput(captured, "Type /help for commands.", 90), diagnosticOutput(captured))
            process.outputStream.write(input.toByteArray(StandardCharsets.UTF_8))
            process.outputStream.flush()
            val submittedText = submitted.poll(30, TimeUnit.SECONDS)
            assertTrue(submittedText != null, diagnosticOutput(captured))
            assertTrue(awaitOutput(captured, "\u56DE\u590D\u6B63\u5E38", 30), diagnosticOutput(captured))
            return TerminalResult(
                submitted = submittedText,
                output = strictUtf8(captured.toByteArray()),
            )
        } finally {
            if (process.isAlive) process.destroyForcibly()
            reader.join(5_000)
            server.stop(0)
            home.toFile().deleteRecursively()
        }
    }

    private fun startServer(submitted: ArrayBlockingQueue<String>): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/health") { exchange ->
                exchange.respond("""{"status":"ready"}""")
            }
            createContext("/api/v1/chat") { exchange ->
                val body = exchange.requestBody.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                val text = Json.parseToJsonElement(body).jsonObject.getValue("text").jsonPrimitive.content
                submitted.offer(text)
                exchange.respond(
                    """{"requestId":"terminal-test","status":"completed","response":"\u56DE\u590D\u6B63\u5E38","error":null}""",
                )
            }
            start()
        }

    private fun HttpExchange.respond(body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun awaitOutput(captured: ByteArrayOutputStream, expected: String, timeoutSeconds: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (System.nanoTime() < deadline) {
            if (captured.toString(StandardCharsets.UTF_8).contains(expected)) return true
            Thread.sleep(50)
        }
        return false
    }

    private fun strictUtf8(bytes: ByteArray): String = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()

    private fun diagnosticOutput(captured: ByteArrayOutputStream): String =
        captured.toString(StandardCharsets.UTF_8).takeLast(8_000)

    private fun enabled(): Boolean =
        System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true) &&
            System.getenv("OPENEDEN_TERMINAL_E2E") == "1"

    private data class TerminalResult(
        val submitted: String,
        val output: String,
    )
}
