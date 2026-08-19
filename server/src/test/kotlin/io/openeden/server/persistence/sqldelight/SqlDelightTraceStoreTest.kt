package io.openeden.server.persistence.sqldelight

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.openeden.server.db.Database
import io.openeden.trace.TraceContext
import io.openeden.trace.TraceSpan
import io.openeden.trace.TraceStatus
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SqlDelightTraceStoreTest {
    private val tempDir = Files.createTempDirectory("openeden-trace-test")
    private val dbPath = tempDir.resolve("openeden.db")

    @AfterTest
    fun cleanup() {
        Files.list(tempDir).use { stream -> stream.forEach { Files.deleteIfExists(it) } }
        Files.deleteIfExists(tempDir)
    }

    @Test
    fun `trace spans survive database restart`() = runTest {
        SqlDelightTraceStore.open(dbPath).let { store ->
            store.append(
                TraceSpan(
                    context = TraceContext("trace", "turn", "S"),
                    spanId = "span",
                    stage = "commit",
                    status = TraceStatus.OK,
                    startedAtMs = 1,
                ),
            )
            store.close()
        }
        SqlDelightTraceStore.open(dbPath).let { reopened ->
            assertEquals("commit", reopened.readAll().single().stage)
            reopened.close()
        }
    }

    @Test
    fun `append executes sqlite write away from caller thread`() = runTest {
        val delegate = JdbcSqliteDriver(
            "jdbc:sqlite:${dbPath.toAbsolutePath()}",
            Properties(),
            Database.Schema,
        )
        val recordingDriver = RecordingSqlDriver(delegate)
        val store = SqlDelightTraceStore(Database(recordingDriver), recordingDriver)
        val callerThread = Thread.currentThread().name

        store.append(
            TraceSpan(
                context = TraceContext("trace-io", "turn-io", "S"),
                spanId = "span-io",
                stage = "io",
                status = TraceStatus.OK,
                startedAtMs = 1,
            ),
        )

        assertNotEquals(callerThread, recordingDriver.lastExecuteThread)
        store.close()
    }

    private class RecordingSqlDriver(
        private val delegate: SqlDriver,
    ) : SqlDriver by delegate {
        var lastExecuteThread: String? = null

        override fun execute(
            identifier: Int?,
            sql: String,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<Long> {
            lastExecuteThread = Thread.currentThread().name
            return delegate.execute(identifier, sql, parameters, binders)
        }
    }
}
