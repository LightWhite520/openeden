package io.openeden.server.persistence.sqldelight

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.openeden.transcript.ConversationTurn
import io.openeden.transcript.HistoryCursor
import io.openeden.transcript.InvalidHistoryCursorException
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SqlDelightTranscriptStoreTest {
    private val tempDir = Files.createTempDirectory("openeden-transcript-test")
    private val dbPath = tempDir.resolve("nested").resolve("openeden.db")

    @AfterTest
    fun cleanup() {
        Files.walk(tempDir).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    @Test
    fun `active incarnation and turns survive close and reopen`() = runTest {
        val first = SqlDelightTranscriptStore.open(dbPath)
        val incarnation = first.activeIncarnation()
        first.append(turn(1, incarnation.id))
        first.close()

        val reopened = SqlDelightTranscriptStore.open(dbPath)
        try {
            assertEquals(incarnation, reopened.activeIncarnation())
            assertEquals(listOf(turn(1, incarnation.id)), reopened.page(limit = 50).turns)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun `appending the same turn twice stores one copy`() = runTest {
        val store = SqlDelightTranscriptStore.open(dbPath)
        try {
            val turn = turn(1, store.activeIncarnation().id)
            store.append(turn)
            store.append(turn)

            assertEquals(listOf(turn), store.page(limit = 50).turns)
        } finally {
            store.close()
        }
    }

    @Test
    fun `duplicate turn id with a different payload is rejected`() = runTest {
        val store = SqlDelightTranscriptStore.open(dbPath)
        try {
            val turn = turn(1, store.activeIncarnation().id)
            store.append(turn)

            assertFailsWith<IllegalArgumentException> {
                store.append(turn.copy(assistantText = "different"))
            }
            assertEquals(listOf(turn), store.page(limit = 50).turns)
        } finally {
            store.close()
        }
    }

    @Test
    fun `pagination returns chronological pages without overlap`() = runTest {
        val store = SqlDelightTranscriptStore.open(dbPath)
        try {
            val incarnationId = store.activeIncarnation().id
            repeat(55) { index -> store.append(turn(index, incarnationId)) }

            val latest = store.page(limit = 50)
            assertEquals((5 until 55).map { "turn-$it" }, latest.turns.map { it.turnId })
            assertTrue(latest.hasMore)
            assertEquals(HistoryCursor(incarnationId, 5L, "turn-5"), assertNotNull(latest.before))

            val older = store.page(limit = 50, before = latest.before)
            assertEquals((0 until 5).map { "turn-$it" }, older.turns.map { it.turnId })
            assertFalse(older.hasMore)
            assertEquals(null, older.before)
            assertTrue(latest.turns.map { it.turnId }.intersect(older.turns.map { it.turnId }.toSet()).isEmpty())
        } finally {
            store.close()
        }
    }

    @Test
    fun `pagination uses turn id as an exclusive tie breaker and clamps limit`() = runTest {
        val store = SqlDelightTranscriptStore.open(dbPath)
        try {
            val incarnationId = store.activeIncarnation().id
            store.append(turn(1, incarnationId).copy(turnId = "turn-a", completedAtMs = 10L))
            store.append(turn(2, incarnationId).copy(turnId = "turn-b", completedAtMs = 10L))
            store.append(turn(3, incarnationId).copy(turnId = "turn-c", completedAtMs = 10L))

            val newest = store.page(limit = 0)
            assertEquals(listOf("turn-c"), newest.turns.map { it.turnId })
            assertTrue(newest.hasMore)
            val older = store.page(limit = 50, before = newest.before)
            assertEquals(listOf("turn-a", "turn-b"), older.turns.map { it.turnId })
        } finally {
            store.close()
        }
    }

    @Test
    fun `cross incarnation turns and cursors are rejected`() = runTest {
        val store = SqlDelightTranscriptStore.open(dbPath)
        try {
            val active = store.activeIncarnation()

            assertFailsWith<IllegalArgumentException> {
                store.append(turn(1, "another-incarnation"))
            }
            assertFailsWith<InvalidHistoryCursorException> {
                store.page(
                    limit = 10,
                    before = HistoryCursor("another-incarnation", 10L, "turn-10"),
                )
            }
            assertEquals(active, store.activeIncarnation())
            assertEquals(emptyList(), store.page(limit = 50).turns)
        } finally {
            store.close()
        }
    }

    @Test
    fun `version four database migrates to transcript schema`() = runTest {
        Files.createDirectories(dbPath.parent)
        JdbcSqliteDriver("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { driver ->
            driver.execute(
                null,
                """
                CREATE TABLE session_state (
                    session_id TEXT NOT NULL PRIMARY KEY,
                    vector_json TEXT NOT NULL,
                    origin_json TEXT NOT NULL,
                    omega REAL NOT NULL,
                    evolution_index INTEGER NOT NULL,
                    last_user_activity_ms INTEGER,
                    shock_active INTEGER,
                    shock_intensity REAL,
                    shock_description TEXT,
                    shock_triggered_at_ms INTEGER,
                    shock_decay_lambda REAL,
                    shock_heartbeat_fired INTEGER
                )
                """.trimIndent(),
                0,
            )
            driver.execute(null, "PRAGMA user_version = 4", 0)
        }

        val store = SqlDelightTranscriptStore.open(dbPath)
        try {
            val incarnation = store.activeIncarnation()
            store.append(turn(1, incarnation.id))
            assertEquals(listOf("turn-1"), store.page(limit = 50).turns.map { it.turnId })
        } finally {
            store.close()
        }
    }

    private fun turn(index: Int, incarnationId: String): ConversationTurn = ConversationTurn(
        turnId = "turn-$index",
        incarnationId = incarnationId,
        sessionId = "QQ:group-1",
        platform = "QQ",
        scopeId = "group-1",
        userId = "user-1",
        userText = "user-$index",
        assistantText = "assistant-$index",
        completedAtMs = index.toLong(),
    )
}
