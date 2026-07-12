package io.openeden

import io.openeden.client.ChatResponse
import io.openeden.client.OpenEdenServerApi
import io.openeden.client.PublicState
import io.openeden.config.CliConfig
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenEdenCliTest {
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

        override suspend fun state(userId: String): PublicState {
            stateCalls += 1
            return PublicState("CLI:$userId", "ready", 0.2f, false)
        }

        override fun close() {
            closeCalls += 1
        }
    }
}
