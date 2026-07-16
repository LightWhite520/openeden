package io.openeden.server.api.route

import io.openeden.server.api.dto.ConversationHistoryPageDto
import io.openeden.server.api.plugin.configureSerialization
import io.openeden.server.api.plugin.configureStatusPages
import io.openeden.server.bootstrap.TranscriptStoreKey
import io.openeden.server.bootstrap.PipelineKey
import io.openeden.server.bootstrap.loadDefaultPersonaConfig
import io.openeden.llm.LlmOutput
import io.openeden.llm.LlmStreamEvent
import io.openeden.llm.StreamingLlmClient
import io.openeden.prompt.BuiltPrompt
import io.openeden.runtime.pipeline.DevelopmentMessagePipeline
import io.openeden.runtime.session.MutableSessionStateStore
import io.openeden.transcript.ActiveIncarnation
import io.openeden.transcript.ConversationHistoryPage
import io.openeden.transcript.ConversationTurn
import io.openeden.transcript.HistoryCursor
import io.openeden.transcript.InMemoryTranscriptStore
import io.openeden.transcript.TranscriptStore
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConversationHistoryApiTest {
    private val json = Json { ignoreUnknownKeys = false }

    @Test
    fun `history returns newest page in chronological order and pages backward`() = runTest {
        val store = seededStore()

        testApplication {
            application {
                attributes.put(TranscriptStoreKey, store)
                configureSerialization()
                configureStatusPages()
                configureWebsockets()
                configureRouting()
            }

            val newestResponse = client.get("/api/v1/history?limit=2")
            assertEquals(HttpStatusCode.OK, newestResponse.status)
            val newestText = newestResponse.bodyAsText()
            val newest = json.decodeFromString<ConversationHistoryPageDto>(newestText)
            assertEquals(listOf("turn-2", "turn-3"), newest.turns.map { it.turnId })
            assertTrue(newest.hasMore)
            val before = assertNotNull(newest.before)
            assertTrue(before.isNotBlank())
            assertPublicTranscriptOnly(newestText)

            val olderResponse = client.get("/api/v1/history?limit=2&before=$before")
            assertEquals(HttpStatusCode.OK, olderResponse.status)
            val older = json.decodeFromString<ConversationHistoryPageDto>(olderResponse.bodyAsText())
            assertEquals(listOf("turn-1"), older.turns.map { it.turnId })
            assertFalse(older.hasMore)
            assertNull(older.before)
        }
    }

    @Test
    fun `history rejects malformed and cross-incarnation cursors with a generic message`() = runTest {
        val store = seededStore()
        val crossIncarnation = HistoryCursorCodec.encode(
            HistoryCursor("old-incarnation", completedAtMs = 2L, turnId = "turn-2"),
        )
        val canonical = HistoryCursorCodec.encode(
            HistoryCursor("active-incarnation", completedAtMs = 2L, turnId = "turn-2"),
        )
        val padded = java.util.Base64.getUrlEncoder().encodeToString(
            java.util.Base64.getUrlDecoder().decode(canonical),
        )
        assertTrue(padded.endsWith('='))

        testApplication {
            application {
                attributes.put(TranscriptStoreKey, store)
                configureSerialization()
                configureStatusPages()
                configureWebsockets()
                configureRouting()
            }

            (listOf("not-base64!", padded, crossIncarnation) + structurallyInvalidCursors())
                .forEach { cursor ->
                val response = client.get("/api/v1/history?before=$cursor")
                assertEquals(HttpStatusCode.BadRequest, response.status)
                assertEquals("Invalid history cursor", response.bodyAsText())
            }
        }
    }

    @Test
    fun `history rejects non-numeric limit and clamps numeric limits`() = runTest {
        val store = seededStore()

        testApplication {
            application {
                attributes.put(TranscriptStoreKey, store)
                configureSerialization()
                configureStatusPages()
                configureWebsockets()
                configureRouting()
            }

            val invalid = client.get("/api/v1/history?limit=two")
            assertEquals(HttpStatusCode.BadRequest, invalid.status)

            val clampedLow = json.decodeFromString<ConversationHistoryPageDto>(
                client.get("/api/v1/history?limit=0").bodyAsText(),
            )
            assertEquals(1, clampedLow.turns.size)

            val clampedHigh = json.decodeFromString<ConversationHistoryPageDto>(
                client.get("/api/v1/history?limit=999").bodyAsText(),
            )
            assertEquals(3, clampedHigh.turns.size)
        }
    }

    @Test
    fun `history never accepts an incarnation selector`() = runTest {
        val store = seededStore()

        testApplication {
            application {
                attributes.put(TranscriptStoreKey, store)
                configureSerialization()
                configureStatusPages()
                configureWebsockets()
                configureRouting()
            }

            val response = client.get("/api/v1/history?incarnationId=old-incarnation")
            assertEquals(HttpStatusCode.OK, response.status)
            val page = json.decodeFromString<ConversationHistoryPageDto>(response.bodyAsText())
            assertEquals(listOf("turn-1", "turn-2", "turn-3"), page.turns.map { it.turnId })
        }
    }

    @Test
    fun `cursor codec is stable and rejects invalid payloads`() {
        val cursor = HistoryCursor("incarnation-a", completedAtMs = 42L, turnId = "turn-42")
        val encoded = HistoryCursorCodec.encode(cursor)
        val padded = java.util.Base64.getUrlEncoder().encodeToString(
            java.util.Base64.getUrlDecoder().decode(encoded),
        )
        assertTrue(padded.endsWith('='))

        assertEquals(encoded, HistoryCursorCodec.encode(cursor))
        assertFalse(encoded.contains('='))
        assertEquals(cursor, HistoryCursorCodec.decode(encoded))
        (listOf(
            "",
            "=",
            padded,
            nonCanonicalPadBits(encoded),
            "游标",
            "%%%",
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("{}".toByteArray()),
            java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"incarnationId\":\"x\"}".toByteArray()),
        ) + structurallyInvalidCursors()).forEach { invalid ->
            assertFailsWith<io.openeden.transcript.InvalidHistoryCursorException> {
                HistoryCursorCodec.decode(invalid)
            }
        }
        listOf(
            HistoryCursor("bad id", 42L, "turn-42"),
            HistoryCursor("incarnation-a", -1L, "turn-42"),
            HistoryCursor("incarnation-a", 42L, "t".repeat(129)),
        ).forEach { invalid ->
            assertFailsWith<io.openeden.transcript.InvalidHistoryCursorException> {
                HistoryCursorCodec.encode(invalid)
            }
        }
    }

    @Test
    fun `streaming retry with the same client request id commits one turn and advances once`() = runTest {
        val transcripts = InMemoryTranscriptStore("active-incarnation", createdAtMs = 0L)
        val stateStore = MutableSessionStateStore(transcriptStore = transcripts)
        val clientRequestId = "id_" + "a".repeat(125)
        val output = LlmOutput(
            internalLogic = "private",
            vectorDelta = listOf("L", "P", "E", "S", "tau", "V", "M", "F")
                .associateWith { 0.0f },
            response = "hello",
        )
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = loadDefaultPersonaConfig(),
            llmClient = object : StreamingLlmClient {
                override val supportsStrictStructuredStreaming = true
                override fun stream(prompt: BuiltPrompt): Flow<LlmStreamEvent> =
                    flowOf(LlmStreamEvent.Completed(output))
                override suspend fun complete(prompt: BuiltPrompt): LlmOutput = output
            },
            store = stateStore,
            transcriptStore = transcripts,
            nowMs = { 100L },
        )

        testApplication {
            application {
                attributes.put(PipelineKey, pipeline)
                attributes.put(TranscriptStoreKey, transcripts)
                configureSerialization()
                configureStatusPages()
                configureWebsockets()
                configureRouting()
            }

            val responses = List(2) {
                client.post("/api/v1/chat/stream") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"userId":"local","text":"hello","clientRequestId":"$clientRequestId"}""")
                }
            }
            responses.forEach { response ->
                assertEquals(HttpStatusCode.OK, response.status)
                val body = response.bodyAsText()
                assertTrue(body.contains("event: accepted"))
                assertTrue(body.contains("event: completed"))
                assertFalse(body.contains("event: error"))
            }

            assertEquals(
                listOf(clientRequestId),
                transcripts.page(50).turns.map { it.turnId },
            )
            assertEquals(1L, stateStore.read("CLI:local").evolutionIndex)
        }
    }

    @Test
    fun `streaming rejects invalid global idempotency keys`() = testApplication {
        application {
            configureSerialization()
            configureStatusPages()
            configureWebsockets()
            configureRouting()
        }

        listOf("", "a".repeat(129), "not valid", "请求", "bad.id").forEach { requestId ->
            val response = client.post("/api/v1/chat/stream") {
                contentType(ContentType.Application.Json)
                setBody("""{"userId":"local","text":"hello","clientRequestId":"$requestId"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("Invalid client request ID", response.bodyAsText())
        }
    }

    @Test
    fun `history store failures return a generic service response`() = testApplication {
        val store = object : TranscriptStore {
            override suspend fun activeIncarnation() = ActiveIncarnation("active", 0L)
            override suspend fun append(turn: ConversationTurn) = Unit
            override suspend fun page(limit: Int, before: HistoryCursor?): ConversationHistoryPage {
                throw IllegalStateException("SELECT secret_value FROM private_table")
            }
        }
        application {
            attributes.put(TranscriptStoreKey, store)
            configureSerialization()
            configureStatusPages()
            configureWebsockets()
            configureRouting()
        }

        val response = client.get("/api/v1/history")

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("History unavailable", response.bodyAsText())
        assertFalse(response.bodyAsText().contains("SELECT"))
        assertFalse(response.bodyAsText().contains("private_table"))
    }

    private suspend fun seededStore(): InMemoryTranscriptStore =
        InMemoryTranscriptStore("active-incarnation", createdAtMs = 0L).also { store ->
            (1L..3L).forEach { number ->
                store.append(
                    ConversationTurn(
                        turnId = "turn-$number",
                        incarnationId = "active-incarnation",
                        sessionId = "CLI:scope-$number",
                        platform = "CLI",
                        scopeId = "scope-$number",
                        userId = "user-$number",
                        userText = "user text $number",
                        assistantText = "assistant text $number",
                        completedAtMs = number,
                    ),
                )
            }
        }

    private fun assertPublicTranscriptOnly(body: String) {
        listOf(
            "internal_logic",
            "vector",
            "omega",
            "shock",
            "prompt",
            "incarnationId",
            "sessionId",
        ).forEach { privateField ->
            assertFalse(body.contains(privateField, ignoreCase = true), privateField)
        }
        assertTrue(body.contains("completedAtMs"))
    }

    private fun nonCanonicalPadBits(canonical: String): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val remainder = canonical.length % 4
        require(remainder == 2 || remainder == 3)
        val lastIndex = alphabet.indexOf(canonical.last())
        val alternative = when (remainder) {
            2 -> (lastIndex and 0b110000) or ((lastIndex + 1) and 0b001111)
            else -> (lastIndex and 0b111100) or ((lastIndex + 1) and 0b000011)
        }
        require(alternative != lastIndex)
        return canonical.dropLast(1) + alphabet[alternative]
    }

    private fun canonicalCursorJson(): String =
        """{"incarnationId":"incarnation-a","completedAtMs":42,"turnId":"turn-42"}"""

    private fun encodeCursorJson(value: String): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())

    private fun structurallyInvalidCursors(): List<String> = listOf(
        "A".repeat(1025),
        encodeCursorJson(" " + canonicalCursorJson()),
        encodeCursorJson(
            """{"turnId":"turn-42","completedAtMs":42,"incarnationId":"incarnation-a"}""",
        ),
        encodeCursorJson(canonicalCursorJson().dropLast(1) + ",\"unknown\":true}"),
        encodeCursorJson(
            """{"incarnationId":"incarnation-a","completedAtMs":42,"turnId":"first","turnId":"second"}""",
        ),
        encodeCursorJson(
            """{"incarnationId":"${"a".repeat(129)}","completedAtMs":42,"turnId":"turn-42"}""",
        ),
        encodeCursorJson(
            """{"incarnationId":"bad id","completedAtMs":42,"turnId":"turn-42"}""",
        ),
        encodeCursorJson(
            """{"incarnationId":"incarnation-a","completedAtMs":42,"turnId":""}""",
        ),
        encodeCursorJson(
            """{"incarnationId":"incarnation-a","completedAtMs":42,"turnId":"${"t".repeat(129)}"}""",
        ),
        encodeCursorJson(
            """{"incarnationId":"incarnation-a","completedAtMs":-1,"turnId":"turn-42"}""",
        ),
        encodeCursorJson(
            canonicalCursorJson().dropLast(1) + ",\"padding\":\"${"x".repeat(520)}\"}",
        ),
    )
}
