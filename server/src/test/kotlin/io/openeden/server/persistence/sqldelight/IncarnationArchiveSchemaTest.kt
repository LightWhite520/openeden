package io.openeden.server.persistence.sqldelight

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.openeden.server.db.Database
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith

class IncarnationArchiveSchemaTest {
    private val tempDir = Files.createTempDirectory("openeden-archive-schema-test")
    private val dbPath = tempDir.resolve("openeden.db")
    private var store: SqlDelightTranscriptStore? = null

    @AfterTest
    fun cleanup() {
        runBlocking { store?.close() }
        Files.deleteIfExists(dbPath)
        Files.deleteIfExists(dbPath.resolveSibling("openeden.db.init.lock"))
        Files.deleteIfExists(tempDir)
    }

    @Test
    fun `opening the database installs the diary archive schema`() = runTest {
        store = SqlDelightTranscriptStore.open(dbPath)

        DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA user_version").use { result ->
                    assertEquals(true, result.next())
                    assertEquals(Database.Schema.version, result.getLong(1))
                }
                statement.executeQuery(
                    "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'diary_archive'",
                ).use { result ->
                    assertEquals(true, result.next())
                    assertNotNull(result.getString(1))
                }
            }
        }
    }

    @Test
    fun `migration to schema 23 rejects multiple incomplete reset sagas deterministically`() {
        val migrationDb = tempDir.resolve("duplicate-sagas.db")
        val driver = JdbcSqliteDriver("jdbc:sqlite:${migrationDb.toAbsolutePath()}")
        try {
            driver.execute(
                null,
                """CREATE TABLE incarnation_reset_requests (
                    request_id TEXT NOT NULL PRIMARY KEY,
                    previous_incarnation_id TEXT NOT NULL,
                    phase TEXT NOT NULL
                )""".trimIndent(),
                0,
            ).value
            driver.execute(
                null,
                "CREATE UNIQUE INDEX incarnation_reset_one_incomplete_per_incarnation " +
                    "ON incarnation_reset_requests(previous_incarnation_id) WHERE phase <> 'COMPLETED'",
                0,
            ).value
            driver.execute(
                null,
                "INSERT INTO incarnation_reset_requests VALUES " +
                    "('request-a','old-a','PREPARED'),('request-b','old-b','PROJECTIONS_VERIFIED')",
                0,
            ).value

            assertFailsWith<Exception> { Database.Schema.migrate(driver, 22L, 23L).value }
            val oldIndexStillPresent = driver.executeQuery(
                null,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' " +
                    "AND name = 'incarnation_reset_one_incomplete_per_incarnation'",
                { cursor -> app.cash.sqldelight.db.QueryResult.Value(cursor.next().value && cursor.getLong(0) == 1L) },
                0,
            ).value
            assertEquals(true, oldIndexStillPresent)
        } finally {
            driver.close()
            Files.deleteIfExists(migrationDb)
        }
    }
}
