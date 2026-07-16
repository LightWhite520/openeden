package io.openeden.cli.application

import io.openeden.cli.input.CliInput
import io.openeden.cli.terminal.JLineTerminalSession
import io.openeden.client.OpenEdenServerClient
import io.openeden.config.CliConfig
import io.openeden.server.api.plugin.configureSerialization
import io.openeden.server.api.plugin.configureStatusPages
import io.openeden.server.api.route.configureRouting
import io.openeden.server.api.route.configureWebsockets
import io.openeden.server.bootstrap.TranscriptStoreKey
import io.openeden.server.persistence.sqldelight.SqlDelightTranscriptStore
import io.openeden.transcript.ConversationTurn
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.plugin
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.UTF_8
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.jline.terminal.TerminalBuilder

class TranscriptRestartRestorationE2ETest {
    @Test
    fun `server restart restores latest fifty turns before CLI input`() = testApplication {
        val directory = createTempDirectory("openeden-transcript-restart")
        val dbPath = directory.resolve("runtime.db")
        val firstStore = SqlDelightTranscriptStore.open(dbPath)
        val incarnation = firstStore.activeIncarnation()
        try {
            (1..51).forEach { index ->
                firstStore.append(turn(index, incarnation.id))
            }
        } finally {
            firstStore.close()
        }

        val reopenedStore = SqlDelightTranscriptStore.open(dbPath)
        try {
            application {
                attributes.put(TranscriptStoreKey, reopenedStore)
                configureSerialization()
                configureStatusPages()
                configureWebsockets()
                configureRouting()
            }
            val order = mutableListOf<String>()
            val historyLimits = mutableListOf<String?>()
            val httpClient = createClient {
                install(ContentNegotiation) { json() }
            }
            httpClient.plugin(HttpSend).intercept { request ->
                if (request.url.toString().substringBefore('?').endsWith("/api/v1/history")) {
                    order += "history"
                    historyLimits += request.url.parameters["limit"]
                }
                execute(request)
            }
            assertEquals(HttpStatusCode.OK, httpClient.get("/health").status)
            assertEquals(HttpStatusCode.OK, httpClient.get("http://localhost/health").status)
            val serverClient = OpenEdenServerClient(baseUrl = "http://localhost", httpClient = httpClient)
            assertTrue(serverClient.health())
            val terminalOutput = ByteArrayOutputStream()
            var restoredBeforeRead = false
            var firstRead = true
            val terminal = TerminalBuilder.builder()
                .name("openeden-transcript-restart")
                .system(false)
                .streams(ByteArrayInputStream(ByteArray(0)), terminalOutput)
                .dumb(true)
                .build()
            val session = JLineTerminalSession.fromTerminal(
                terminal = terminal,
                historyPath = directory.resolve("cli-history"),
                enterRawMode = false,
                richSupported = false,
                readLine = {
                    if (firstRead) {
                        firstRead = false
                        order += "readLine"
                        restoredBeforeRead = terminalOutput.toString(UTF_8).contains("ATRI: assistant-turn-51")
                        "/exit"
                    } else {
                        null
                    }
                },
            )
            val cli = OpenEdenCli(
                configLoader = { CliConfig(serverUrl = "http://in-process", userId = "local") },
                clientFactory = { serverClient },
                input = object : CliInput {
                    override suspend fun readLine(): String? = null
                },
                terminalSessionFactory = { session },
            )

            assertEquals(0, cli.run(emptyList()))
            assertTrue(restoredBeforeRead, terminalOutput.toString(UTF_8))
            assertEquals(listOf("history", "readLine"), order)
            assertEquals(listOf<String?>("50"), historyLimits)

            val restoredLines = terminalOutput.toString(UTF_8)
                .lineSequence()
                .map(String::trimEnd)
                .filter { line -> line.startsWith("> user-turn-") || line.startsWith("ATRI: assistant-turn-") }
                .toList()
            val expectedLines = (2..51).flatMap { index ->
                listOf("> user-turn-$index", "ATRI: assistant-turn-$index")
            }
            assertEquals(expectedLines, restoredLines)
            assertFalse(restoredLines.contains("> user-turn-1"))
            assertFalse(restoredLines.contains("ATRI: assistant-turn-1"))
        } finally {
            reopenedStore.close()
            directory.toFile().deleteRecursively()
        }
    }

    private fun turn(index: Int, incarnationId: String) = ConversationTurn(
        turnId = "turn-$index",
        incarnationId = incarnationId,
        sessionId = "CLI:local",
        platform = "CLI",
        scopeId = "local",
        userId = "local",
        userText = "user-turn-$index",
        assistantText = "assistant-turn-$index",
        completedAtMs = index.toLong(),
    )
}
