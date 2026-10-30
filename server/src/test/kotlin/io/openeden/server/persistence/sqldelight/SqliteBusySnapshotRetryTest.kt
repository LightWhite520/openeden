package io.openeden.server.persistence.sqldelight

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.openeden.server.db.Database
import java.nio.file.Files
import java.sql.DriverManager
import java.util.Properties
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class SqliteBusySnapshotRetryTest {
    @Test
    fun `busy snapshot rolls back and retries with fresh data`() = runBlocking {
        val directory = Files.createTempDirectory("projection-busy-")
        val url = "jdbc:sqlite:${directory.resolve("runtime.db")}"
        val driver = JdbcSqliteDriver(url, Properties(), Database.Schema)
        try {
            DriverManager.getConnection(url).use { writer ->
                writer.createStatement().use { it.execute("PRAGMA journal_mode=WAL") }
                val database = Database(driver)
                val queries = database.memoryQueries
                queries.upsertVectorSync("memory", "model", "PENDING", 0, 1, null, 1)
                var attempts = 0
                retrySqliteBusy {
                    database.transactionWithResult {
                        val snapshot = queries.selectVectorSync("memory").executeAsOne()
                        if (attempts++ == 0) {
                            // Another connection commits after the deferred transaction has read.
                            writer.createStatement().use {
                                it.executeUpdate("UPDATE memory_vector_sync SET available_at_ms = 2 WHERE memory_id = 'memory'")
                            }
                        } else {
                            assertEquals(2L, snapshot.available_at_ms)
                        }
                        queries.claimVectorSync(3, "memory")
                    }
                }
                assertEquals(2, attempts)
                assertEquals("RUNNING", queries.selectVectorSync("memory").executeAsOne().status)
            }
        } finally {
            driver.close()
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}
