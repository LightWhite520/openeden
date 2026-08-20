package io.openeden.server.persistence.sqldelight

import io.openeden.server.db.Database
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

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
}
