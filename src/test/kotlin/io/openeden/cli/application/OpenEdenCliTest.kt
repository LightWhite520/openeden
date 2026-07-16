package io.openeden.cli.application

import io.openeden.cli.input.CliInput
import io.openeden.cli.terminal.CliTerminalEvent
import io.openeden.cli.terminal.JLineTerminalSession
import io.openeden.cli.terminal.TerminalSession
import io.openeden.client.ChatResponse
import io.openeden.client.ChatStreamEvent
import io.openeden.client.ConversationHistoryPage
import io.openeden.client.DiagnosticState
import io.openeden.client.OpenEdenServerApi
import io.openeden.client.PublicState
import io.openeden.config.CliConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.jline.reader.LineReader
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenEdenCliTest {
    @Test
    fun `supplied terminal remains open while configuration error is reported`() = runTest {
        val output = StringBuilder()
        val session = FakeTerminalSession()
        val cli = OpenEdenCli(
            configLoader = { error("bad config") },
            input = SequenceInput(emptyList()),
            output = output::append,
        )

        assertEquals(2, cli.runWithTerminal(emptyList(), session))
        assertTrue(output.toString().contains("configuration error: bad config"))
        assertEquals(0, session.closeCalls)
    }

    @Test
    fun `supplied terminal remains open while health error is reported`() = runTest {
        val output = StringBuilder()
        val session = FakeTerminalSession()
        val client = FakeServerClient().apply { healthy = false }
        val cli = OpenEdenCli(
            configLoader = { config() },
            clientFactory = { client },
            input = SequenceInput(emptyList()),
            output = output::append,
        )

        assertEquals(1, cli.runWithTerminal(emptyList(), session))
        assertTrue(output.toString().contains("server error:"))
        assertEquals(0, session.closeCalls)
    }

    @Test
    fun `supplied terminal remains open while repl error is reported`() = runTest {
        val output = StringBuilder()
        val session = FakeTerminalSession(events = flow { throw IllegalStateException("repl failed") })
        val cli = OpenEdenCli(
            configLoader = { config() },
            clientFactory = { FakeServerClient() },
            input = SequenceInput(emptyList()),
            output = output::append,
        )

        assertEquals(1, cli.runWithTerminal(emptyList(), session))
        assertTrue(output.toString().contains("server error: repl failed"))
        assertEquals(0, session.closeCalls)
    }

    @Test
    fun `factory terminal remains self owned and closes exactly once`() = runTest {
        val session = FakeTerminalSession()
        val cli = OpenEdenCli(
            configLoader = { config() },
            clientFactory = { FakeServerClient() },
            input = SequenceInput(emptyList()),
            output = {},
            terminalSessionFactory = { session },
        )

        assertEquals(0, cli.run(emptyList()))
        assertEquals(1, session.closeCalls)
    }

    @Test
    fun `interactive terminal events drive the session controller`() = runTest {
        val client = FakeServerClient()
        val terminalOutput = ByteArrayOutputStream()
        val errors = StringBuilder()
        val lines = ArrayDeque(listOf("hello", "/exit"))
        val cli = OpenEdenCli(
            configLoader = { config() },
            clientFactory = { client },
            input = SequenceInput(emptyList()),
            output = errors::append,
            terminalSessionFactory = {
                val terminal = TerminalBuilder.builder()
                    .system(false)
                    .streams(java.io.ByteArrayInputStream(ByteArray(0)), terminalOutput)
                    .dumb(true)
                    .build()
                JLineTerminalSession.fromTerminal(
                    terminal = terminal,
                    historyPath = createTempDirectory("openeden-cli-test").resolve("history"),
                    enterRawMode = false,
                    richSupported = false,
                    readLine = { lines.removeFirstOrNull() },
                )
            },
        )

        assertEquals(0, cli.run(emptyList()), errors.toString())
        assertEquals(listOf("hello"), client.messages)
        assertEquals(1, client.closeCalls)
    }

    @Test
    fun `repl sends multiple messages through one server client`() = runTest {
        val client = FakeServerClient()
        val output = StringBuilder()
        val cli = testCli(client, listOf("hello", "second", "/exit"), output)

        assertEquals(0, cli.run(emptyList()))
        assertEquals(listOf("hello", "second"), client.messages)
        assertEquals(1, client.closeCalls)
        assertFalse(output.toString().contains("evolutionIndex"))
        assertFalse(output.toString().contains("traceTags"))
    }

    @Test
    fun `repl handles local commands and ignores blank input`() = runTest {
        val client = FakeServerClient()
        val output = StringBuilder()
        val cli = testCli(client, listOf("", "/help", "/state", "/exit"), output)

        assertEquals(0, cli.run(emptyList()))
        assertEquals(1, client.stateCalls)
        assertTrue(output.toString().contains("/state  /history older  /help  /exit"))
        assertTrue(output.toString().contains("sessionId=CLI:local"))
    }

    @Test
    fun `exit only closes http client and does not stop server`() = runTest {
        val client = FakeServerClient()
        val cli = testCli(client, listOf("/exit"), StringBuilder())

        cli.run(emptyList())

        assertEquals(1, client.closeCalls)
        assertFalse(client.serverStopRequested)
    }

    @Test
    fun `compatibility chat command uses public http response`() = runTest {
        val client = FakeServerClient()
        val output = StringBuilder()
        val cli = testCli(client, emptyList(), output)

        assertEquals(0, cli.run(listOf("chat", "--message", "hello")))
        assertEquals("response\n", output.toString())
    }

    private fun testCli(client: FakeServerClient, lines: List<String>, output: StringBuilder): OpenEdenCli =
        OpenEdenCli(
            configLoader = { config() },
            clientFactory = { client },
            input = SequenceInput(lines),
            output = { output.append(it) },
        )

    private class SequenceInput(lines: List<String>) : CliInput {
        private val remaining = ArrayDeque(lines)

        override suspend fun readLine(): String? = remaining.removeFirstOrNull()
    }

    private fun config() = CliConfig(
        serverUrl = "http://127.0.0.1:8080",
        userId = "local",
    )

    private class FakeServerClient : OpenEdenServerApi {
        val messages = mutableListOf<String>()
        var stateCalls = 0
        var closeCalls = 0
        var serverStopRequested = false
        var healthy = true

        override suspend fun health() = healthy

        override suspend fun chat(userId: String, text: String): ChatResponse {
            messages += text
            return ChatResponse("req_test", "completed", "response")
        }

        override fun chatStream(userId: String, text: String, clientRequestId: String): Flow<ChatStreamEvent> {
            messages += text
            return flowOf(
                ChatStreamEvent.Accepted("req_test"),
                ChatStreamEvent.ResponseDelta("response"),
                ChatStreamEvent.Completed("req_test", "completed"),
            )
        }

        override suspend fun history(limit: Int, before: String?) =
            ConversationHistoryPage(emptyList(), null, false)

        override suspend fun state(userId: String): PublicState {
            stateCalls += 1
            return PublicState("CLI:$userId", "ready", 0.2f, false)
        }

        override suspend fun diagnostics(userId: String, token: String): DiagnosticState =
            DiagnosticState("CLI:$userId", List(8) { 0.0f }, 0.0f, false, null, 0L, 0.0f)

        override fun close() {
            closeCalls += 1
        }
    }

    private class FakeTerminalSession(
        private val events: Flow<CliTerminalEvent> = flowOf(CliTerminalEvent.EndOfFile),
    ) : TerminalSession {
        override val terminal: Terminal = proxy()
        override val lineReader: LineReader = proxy()
        var closeCalls = 0
            private set

        override fun events(): Flow<CliTerminalEvent> = events
        override fun enterFullScreen() = false
        override fun exitFullScreen() = Unit
        override fun redisplay() = Unit
        override fun close() {
            closeCalls += 1
        }

        private inline fun <reified T> proxy(): T = Proxy.newProxyInstance(
            T::class.java.classLoader,
            arrayOf(T::class.java),
        ) { _, method, _ ->
            when {
                method.name == "printAbove" -> null
                method.returnType == java.lang.Boolean.TYPE -> false
                method.returnType == java.lang.Integer.TYPE -> 0
                else -> null
            }
        } as T
    }
}
