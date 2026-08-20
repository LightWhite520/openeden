package io.openeden.server.persistence.sqldelight

import io.openeden.server.persistence.sqldelight.MemoryVectorProjectionStore.ProjectionStatus
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
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
    fun `running work is recovered on startup`() = runTest {
        MemoryVectorProjectionStore.open(dbPath).use { store ->
            store.enqueue("memory-1", "m", 10L)
            store.claimDue(10L, 1)
        }
        MemoryVectorProjectionStore.open(dbPath).use { store ->
            assertEquals(ProjectionStatus.PENDING, store.read("memory-1")?.status)
            assertEquals(listOf("memory-1"), store.claimDue(11L, 10).map { it.memoryId })
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
}
