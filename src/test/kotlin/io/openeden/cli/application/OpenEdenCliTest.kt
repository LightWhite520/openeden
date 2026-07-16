package io.openeden.cli.application

import io.openeden.cli.input.CliInput
import io.openeden.cli.terminal.JLineTerminalSession

import io.openeden.client.ChatResponse
import io.openeden.client.ChatStreamEvent
import io.openeden.client.ConversationHistoryPage
import io.openeden.client.DiagnosticState
import io.openeden.client.OpenEdenServerApi
import io.openeden.client.PublicState
import io.openeden.config.CliConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import kotlin.io.path.createTempDirectory
import org.jline.terminal.TerminalBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenEdenCliTest {
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
        assertTrue(output.toString().contains("/state  /help  /exit"))
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

        override suspend fun health() = true

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
}
