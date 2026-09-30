package io.openeden.server.persistence.sqldelight

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.*

class SqliteBusyRetryTest {
    @Test
    fun `retries a real contended transaction after rollback without duplicating its write`() = runTest {
        withContext(Dispatchers.IO) {
            val path = Files.createTempFile("openeden-busy-retry", ".db")
            try {
                DriverManager.getConnection("jdbc:sqlite:$path").use { holder ->
                    DriverManager.getConnection("jdbc:sqlite:$path").use { writer ->
                        holder.createStatement().use { it.execute("CREATE TABLE counter(value INTEGER)"); it.execute("INSERT INTO counter VALUES (0)") }
                        writer.createStatement().use { it.execute("PRAGMA busy_timeout=0") }
                        holder.createStatement().use { it.execute("BEGIN IMMEDIATE") }
                        writer.autoCommit = false
                        val contended = CompletableDeferred<Unit>()
                        var attempts = 0
                        val job = launch {
                            retrySqliteBusy {
                                attempts++
                                try {
                                    writer.createStatement().use { it.executeUpdate("UPDATE counter SET value=value+1") }
                                    writer.commit()
                                } catch (failure: SQLException) {
                                    writer.rollback()
                                    contended.complete(Unit)
                                    throw failure
                                }
                            }
                        }
                        contended.await()
                        holder.createStatement().use { it.execute("ROLLBACK") }
                        job.join()
                        assertTrue(attempts >= 2)
                        holder.createStatement().use { statement ->
                            statement.executeQuery("SELECT value FROM counter").use { result ->
                                assertTrue(result.next())
                                assertEquals(1, result.getInt(1))
                            }
                        }
                    }
                }
            } finally {
                Files.deleteIfExists(path)
            }
        }
    }

    @Test
    fun `busy retries are bounded and other SQL failures are not retried`() = runTest {
        for ((code, expectedAttempts) in listOf(5 to 6, 517 to 6, 19 to 1)) {
            var attempts = 0
            val failure = SQLException("test", "", code)
            val actual = assertFailsWith<SQLException> {
                retrySqliteBusy { attempts++; throw failure }
            }
            assertSame(failure, actual)
            assertEquals(expectedAttempts, attempts)
        }
    }
}
