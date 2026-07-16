package io.openeden.server.persistence.sqldelight

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import io.openeden.server.db.Database
import io.openeden.transcript.ConversationTurn
import io.openeden.transcript.HistoryCursor
import io.openeden.transcript.InvalidHistoryCursorException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assume
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
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
    fun `failed schema initialization rolls back before normal reopen`() = runTest {
        assertFailsWith<IllegalStateException> {
            SqlDelightTranscriptStore.open(dbPath, failingSchema)
        }
        assertEquals(0L to 0L, schemaState())
        val movedPath = dbPath.resolveSibling("rolled-back.db")
        Files.move(dbPath, movedPath)
        Files.move(movedPath, dbPath)

        val store = SqlDelightTranscriptStore.open(dbPath)
        try {
            store.activeIncarnation()
        } finally {
            store.close()
        }

        assertEquals(Database.Schema.version to 0L, schemaState())
    }

    @Test
    fun `concurrent first opens share one active incarnation`() = runTest {
        val stores = concurrentOpen()
        try {
            val incarnations = stores.map { it.activeIncarnation() }
            assertEquals(1, incarnations.distinct().size)
        } finally {
            stores.forEach { it.close() }
        }
    }

    @Test
    fun `concurrent version four opens preserve and migrate existing data`() = runTest {
        createVersionFourDatabase()

        val stores = concurrentOpen()
        try {
            assertEquals(1, stores.map { it.activeIncarnation() }.distinct().size)
        } finally {
            stores.forEach { it.close() }
        }

        assertVersionFourDataMigrated()
    }

    @Test
    fun `concurrent version four opens through file symlink share initialization`() = runTest {
        createVersionFourDatabase()
        val aliasPath = tempDir.resolve("openeden-alias.db")
        createSymbolicLinkOrSkip(aliasPath, dbPath)

        val stores = concurrentOpen(listOf(dbPath, aliasPath))
        try {
            assertEquals(1, stores.map { it.activeIncarnation() }.distinct().size)
        } finally {
            stores.forEach { it.close() }
        }

        assertVersionFourDataMigrated()
    }

    private fun assertVersionFourDataMigrated() {
        JdbcSqliteDriver("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { driver ->
            val migrated = driver.executeQuery(
                null,
                "SELECT evolution_index, persona_mode, persona_start_sub_state FROM session_state WHERE session_id = ?",
                { cursor ->
                    check(cursor.next().value)
                    QueryResult.Value(Triple(cursor.getLong(0), cursor.getString(1), cursor.getString(2)))
                },
                1,
            ) {
                bindString(0, "QQ:migrated")
            }.value
            assertEquals(Triple(99L, "growth", "awakened"), migrated)
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
        createVersionFourDatabase()

        val store = SqlDelightTranscriptStore.open(dbPath)
        try {
            val incarnation = store.activeIncarnation()
            store.append(turn(1, incarnation.id))
            assertEquals(listOf("turn-1"), store.page(limit = 50).turns.map { it.turnId })
        } finally {
            store.close()
        }
    }

    private suspend fun concurrentOpen(
        paths: List<Path> = listOf(dbPath, dbPath),
    ): List<SqlDelightTranscriptStore> = coroutineScope {
        paths.map { path ->
            async(Dispatchers.IO) { SqlDelightTranscriptStore.open(path) }
        }.awaitAll()
    }

    private fun createSymbolicLinkOrSkip(aliasPath: Path, targetPath: Path) {
        try {
            Files.createSymbolicLink(aliasPath, targetPath.toAbsolutePath())
        } catch (error: UnsupportedOperationException) {
            Assume.assumeNoException("Symbolic links are not supported", error)
        } catch (error: FileSystemException) {
            Assume.assumeNoException("Symbolic links are not permitted", error)
        }
    }

    private fun createVersionFourDatabase() {
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
            driver.execute(
                null,
                """
                INSERT INTO session_state(session_id, vector_json, origin_json, omega, evolution_index)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
                5,
            ) {
                bindString(0, "QQ:migrated")
                bindString(1, "vector")
                bindString(2, "origin")
                bindDouble(3, 0.0)
                bindLong(4, 99L)
            }
            driver.execute(null, "PRAGMA user_version = 4", 0)
        }
    }

    private fun schemaState(): Pair<Long, Long> {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbPath.toAbsolutePath()}")
        return try {
            val version = driver.executeQuery(
                null,
                "PRAGMA user_version",
                { cursor ->
                    check(cursor.next().value)
                    QueryResult.Value(checkNotNull(cursor.getLong(0)))
                },
                0,
            ).value
            val partialTableCount = driver.executeQuery(
                null,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'partial_schema'",
                { cursor ->
                    check(cursor.next().value)
                    QueryResult.Value(checkNotNull(cursor.getLong(0)))
                },
                0,
            ).value
            version to partialTableCount
        } finally {
            driver.close()
        }
    }

    private val failingSchema = object : SqlSchema<QueryResult.Value<Unit>> {
        override val version: Long = Database.Schema.version

        override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
            driver.execute(null, "CREATE TABLE partial_schema(id INTEGER NOT NULL PRIMARY KEY)", 0).value
            error("injected schema failure")
        }

        override fun migrate(
            driver: SqlDriver,
            oldVersion: Long,
            newVersion: Long,
            vararg callbacks: AfterVersion,
        ): QueryResult.Value<Unit> = error("migration is not used by this fixture")
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
