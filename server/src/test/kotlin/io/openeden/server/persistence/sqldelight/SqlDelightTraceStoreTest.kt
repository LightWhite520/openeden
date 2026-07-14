package io.openeden.server.persistence.sqldelight

import io.openeden.server.persistence.sqldelight.SqlDelightTraceStore
import io.openeden.trace.TraceContext
import io.openeden.trace.TraceSpan
import io.openeden.trace.TraceStatus
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
