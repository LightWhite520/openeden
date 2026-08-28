package io.openeden.server.persistence.sqldelight

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.openeden.server.db.Database
import io.openeden.server.persistence.sqldelight.MemoryVectorProjectionStore.ProjectionStatus
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MemoryVectorProjectionStoreTest {
    private val tempDir = Files.createTempDirectory("openeden-vector-projection-test")
    private val dbPath = tempDir.resolve("openeden.db")

    @AfterTest
    fun cleanup() {
        runCatching { Files.walk(tempDir).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        } }
    }

    @Test
    fun `enqueue starts pending with zero attempts and is immediately available`() = runTest {
        val store = MemoryVectorProjectionStore.open(dbPath)
        try {
            store.enqueue("memory-1", "model-a", 100L)
            assertEquals(
                MemoryVectorProjectionStore.ProjectionWork("memory-1", "model-a", ProjectionStatus.PENDING, 0, 100L, null, 100L),
                store.read("memory-1"),
            )
            assertEquals(1L, store.pendingCount())
        } finally { store.close() }
    }

    @Test
    fun `claimDue orders by availability then memory id and respects batch`() = runTest {
        val store = MemoryVectorProjectionStore.open(dbPath)
        try {
            store.enqueue("b", "m", 10L)
            store.enqueue("a", "m", 10L)
            store.enqueue("late", "m", 20L)
            val claimed = store.claimDue(20L, 2)
            assertEquals(listOf("a", "b"), claimed.map { it.memoryId })
            assertTrue(claimed.all { it.status == ProjectionStatus.RUNNING })
            assertEquals(1L, store.pendingCount())
        } finally { store.close() }
    }

    @Test
    fun `claimDue filters to the active model`() = runTest {
        MemoryVectorProjectionStore.open(dbPath).use { store ->
            store.enqueue("old", "model-old", 1L)
            store.enqueue("current", "model-current", 1L)
            assertEquals(listOf("current"), store.claimDue(1L, 10, "model-current").map { it.memoryId })
            assertEquals(ProjectionStatus.PENDING, store.read("old")?.status)
        }
    }

    @Test
    fun `ready acknowledgement and retry preserve durable metadata`() = runTest {
        val store = MemoryVectorProjectionStore.open(dbPath)
        try {
            store.enqueue("memory-1", "m", 10L)
            store.claimDue(10L, 1)
            store.reschedule("memory-1", 20L, "  network failure\nsecret  ")
            val retry = store.read("memory-1")!!
            assertEquals(ProjectionStatus.PENDING, retry.status)
            assertEquals(1, retry.attempts)
            assertEquals(2020L, retry.availableAtMs)
            assertEquals("network failure secret", retry.lastError)
            store.claimDue(2020L, 1)
            store.markReady("memory-1", 2030L)
            assertEquals(ProjectionStatus.READY, store.read("memory-1")?.status)
            assertEquals(0L, store.pendingCount())
        } finally { store.close() }
    }

    @Test
    fun `batch ready acknowledgement marks only running work`() = runTest {
        MemoryVectorProjectionStore.open(dbPath).use { store ->
            store.enqueue("a", "m", 1L)
            store.enqueue("b", "m", 1L)
            store.claimDue(1L, 10, "m")
            store.markReady(listOf("a", "b"), 2L)
            assertTrue(listOf("a", "b").all { store.read(it)?.status == ProjectionStatus.READY })
        }
    }

    @Test
    fun `running work is recovered on startup`() = runTest {
        MemoryVectorProjectionStore.open(dbPath).use { store ->
            store.enqueue("memory-1", "m", 10L)
            store.claimDue(10L, 1)
        }
        MemoryVectorProjectionStore.open(dbPath).use { store ->
            store.recoverRunning(11L)
            assertEquals(ProjectionStatus.PENDING, store.read("memory-1")?.status)
            assertEquals(listOf("memory-1"), store.claimDue(11L, 10).map { it.memoryId })
        }
    }

    @Test
    fun `duplicate enqueue resets pending state and attempts`() = runTest {
        MemoryVectorProjectionStore.open(dbPath).use { store ->
            store.enqueue("memory-1", "m", 1L)
            store.claimDue(1L, 1, "m")
            store.reschedule("memory-1", 2L, "failed")
            store.enqueue("memory-1", "m", 3L)
            val work = store.read("memory-1")!!
            assertEquals(ProjectionStatus.PENDING, work.status)
            assertEquals(0, work.attempts)
            assertEquals(3L, work.availableAtMs)
            assertEquals(null, work.lastError)
        }
    }

    @Test
    fun `model refresh selection includes unknown and incompatible local models`() = runTest {
        JdbcSqliteDriver("jdbc:sqlite:${dbPath.toAbsolutePath()}", Properties(), Database.Schema).use { driver ->
            val queries = Database(driver).memoryQueries
            insertMemory(queries, "unknown-memory")
            queries.upsertEmbedding("unknown-memory", "unknown", "[]", "[]", "READY")
            insertMemory(queries, "old-memory")
            queries.upsertEmbedding("old-memory", "model-old", "[]", "[]", "READY")
        }
        MemoryVectorProjectionStore.open(dbPath).use { store ->
            assertEquals(listOf("old-memory", "unknown-memory"), store.selectModelRefresh("model-current", 10).map { it.memoryId }.sorted())
        }
    }

    @Test
    fun `status and argument validation is enforced`() = runTest {
        val store = MemoryVectorProjectionStore.open(dbPath)
        try {
            assertFailsWith<IllegalArgumentException> { store.claimDue(0L, 0) }
            assertFailsWith<IllegalArgumentException> { store.enqueue("", "m", 1L) }
            assertFailsWith<IllegalArgumentException> { store.markReady("x", -1L) }
        } finally { store.close() }
    }

    @Test
    fun `requeueReady resets a bounded batch after collection recreation`() = runTest {
        MemoryVectorProjectionStore.open(dbPath).use { store ->
            store.enqueue("a", "model", 1L)
            store.enqueue("b", "model", 1L)
            store.claimDue(1L, 10, "model")
            store.markReady(listOf("a", "b"), 2L)

            assertEquals(listOf("a"), store.requeueReady("model", 3L, 1))
            assertEquals(ProjectionStatus.PENDING, store.read("a")?.status)
            assertEquals(ProjectionStatus.READY, store.read("b")?.status)
        }
    }

    @Test
    fun `jittered retry uses configured interval as the exponential base`() = runTest {
        MemoryVectorProjectionStore.open(dbPath).use { store ->
            store.enqueue("a", "model", 10L)
            store.claimDue(10L, 1, "model")
            store.rescheduleWithJitter("a", 10L, "failure", jitterMs = 0L, baseDelayMs = 30_000L)
            assertEquals(30_010L, store.read("a")?.availableAtMs)
            store.claimDue(30_010L, 1, "model")
            store.rescheduleWithJitter("a", 30_010L, "failure", jitterMs = 0L, baseDelayMs = 30_000L)
            assertEquals(90_010L, store.read("a")?.availableAtMs)
        }
    }

    @Test
    fun `projection counts expose due pending and all non ready rows`() = runTest {
        MemoryVectorProjectionStore.open(dbPath).use { store ->
            store.enqueue("a-due", "model", 10L)
            store.enqueue("b-ready", "model", 10L)
            store.enqueue("c-due", "model", 10L)
            store.enqueue("future", "model", 100L)
            store.claimDue(10L, 1, "model")
            store.claimDue(10L, 1, "model")
            store.markReady("a-due", 11L)
            store.markReady("b-ready", 11L)

            assertEquals(
                MemoryVectorProjectionStore.ProjectionCounts(duePending = 1L, nonReady = 2L),
                store.projectionCounts(10L),
            )
        }
    }

    private fun insertMemory(queries: io.openeden.server.db.MemoryQueries, id: String) {
        queries.insertEntry(
            id, "session", "user", "CLI", "event_room", "RAW", "content", "[]", 1L,
            0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
            0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
            0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
            0.0,
            "[]", "[]", null, 1L,
            "incarnation", "session", "subject", "scope_shared", null, "session",
        )
    }
}
