package io.openeden.server.persistence.sqldelight

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.openeden.runtime.diary.DiaryTask
import io.openeden.runtime.diary.DiaryTaskStatus
import io.openeden.server.db.Database
import java.nio.file.Files
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Properties
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SqlDelightDiaryTaskContentionTest {
    @Test
    fun `expired lease recovery retries a real competing SQLite writer`() = runTest {
        withContext(Dispatchers.IO) {
            val directory = Files.createTempDirectory("diary-busy-")
            val url = "jdbc:sqlite:${directory.resolve("runtime.db")}"
            val driver = JdbcSqliteDriver(url, Properties().apply { setProperty("busy_timeout", "0") }, Database.Schema)
            val contended = CompletableDeferred<Unit>()
            val failures = AtomicInteger()
            val recordingDriver = object : SqlDriver by driver {
                override fun execute(identifier: Int?, sql: String, parameters: Int,
                    binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<Long> {
                    try {
                        return driver.execute(identifier, sql, parameters, binders)
                    } catch (failure: SQLException) {
                        if ((failure.errorCode and 0xff) == 5) {
                            failures.incrementAndGet()
                            contended.complete(Unit)
                        }
                        throw failure
                    }
                }
            }
            val store = SqlDelightDiaryTaskStore(Database(recordingDriver))
            try {
                store.enqueue(DiaryTask("task:expired", "S", null, "delta"))
                store.leaseNext("S", 10, 10)
                DriverManager.getConnection(url).use { holder ->
                    holder.createStatement().use { it.execute("BEGIN IMMEDIATE") }
                    val recovery = launch { store.recoverExpired(21) }
                    contended.await()
                    holder.createStatement().use { it.execute("ROLLBACK") }
                    recovery.join()
                }
                assertTrue(failures.get() >= 1)
                assertEquals(DiaryTaskStatus.PENDING, store.readById("task:expired")?.status)
                assertEquals(null, store.readById("task:expired")?.leaseToken)
            } finally {
                driver.close()
                Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            }
        }
    }
}
