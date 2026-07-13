package io.openeden.server

import io.openeden.runtime.DiaryTask
import io.openeden.runtime.DiaryTaskStatus
import io.openeden.runtime.DiaryCheckpoint
import io.openeden.server.db.SqlDelightDiaryTaskStore
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SqlDelightDiaryTaskStoreTest {
    private val tempDir = Files.createTempDirectory("openeden-diary-test")
    private val dbPath = tempDir.resolve("openeden.db")

    @AfterTest
    fun cleanup() {
        runCatching { Files.list(tempDir).use { stream -> stream.forEach { Files.deleteIfExists(it) } } }
        runCatching { Files.deleteIfExists(tempDir) }
    }

    @Test
    fun `queue is bounded and expired leases recover`() = runTest {
        val store = SqlDelightDiaryTaskStore.open(dbPath)
        repeat(8) { index ->
            assertTrue(store.enqueue(DiaryTask("task:$index", "S", null, "delta")).isEmpty())
        }
        assertTrue(store.enqueue(DiaryTask("task:overflow", "S", null, "delta")).isNotEmpty())
        val leased = store.leaseNext("S", 100, 10)
        assertEquals("task:0", leased?.id)
        store.recoverExpired(111)
        assertEquals(DiaryTaskStatus.PENDING, store.readById("task:0")?.status)
        store.close()
    }

    @Test
    fun `idempotent enqueue ignores duplicate deterministic task id`() = runTest {
        val store = SqlDelightDiaryTaskStore.open(dbPath)
        val task = DiaryTask("S|vector_delta|raw-1", "S", "raw-1", "vector_delta")

        assertTrue(store.enqueueIfAbsent(task).isEmpty())
        assertTrue(store.enqueueIfAbsent(task.copy(availableAtMs = 99L)).isEmpty())
        assertEquals(task.id, store.readById(task.id)?.id)
        assertEquals(task.availableAtMs, store.readById(task.id)?.availableAtMs)
        store.close()
    }

    @Test
    fun `checkpoint survives restart and completion advances it atomically`() = runTest {
        val store = SqlDelightDiaryTaskStore.open(dbPath)
        val task = DiaryTask("task:checkpoint", "S", "raw-9", "delta")
        store.enqueueIfAbsent(task)
        val leased = store.leaseNext("S", 10, 100)
        assertEquals("task:checkpoint", leased?.id)
        store.completeWithCheckpoint(task.id, leased!!.leaseToken!!, DiaryCheckpoint("raw-9", 20, "narrative-9"))
        assertEquals(DiaryTaskStatus.DONE, store.readById(task.id)?.status)
        assertEquals(DiaryCheckpoint("raw-9", 20, "narrative-9"), store.readCheckpoint("S"))
        store.close()

        SqlDelightDiaryTaskStore.open(dbPath).use { reopened ->
            assertEquals(DiaryCheckpoint("raw-9", 20, "narrative-9"), reopened.readCheckpoint("S"))
        }
    }

    @Test
    fun `concurrent duplicate enqueue remains bounded atomically`() = runTest {
        val store = SqlDelightDiaryTaskStore.open(dbPath)
        kotlinx.coroutines.coroutineScope {
            repeat(16) { launch { store.enqueueIfAbsent(DiaryTask("same", "S", null, "delta")) } }
        }
        assertEquals(1, store.countActive("S"))
        store.close()
    }

    @Test
    fun `competing workers lease a task at most once`() = runTest {
        SqlDelightDiaryTaskStore.open(dbPath).use { it.enqueue(DiaryTask("S:1700000000000:task", "S", null, "delta")) }
        val stores = listOf(SqlDelightDiaryTaskStore.open(dbPath), SqlDelightDiaryTaskStore.open(dbPath))
        try {
            val leases = stores.map { store -> async(Dispatchers.IO) { store.leaseNext("S", 1_700_000_000_001L, 10_000L) } }.awaitAll()
            assertEquals(1, leases.count { it != null })
        } finally {
            stores.forEach { it.close() }
        }
    }

    @Test
    fun `stale lease cannot complete checkpoint`() = runTest {
        val store = SqlDelightDiaryTaskStore.open(dbPath)
        store.enqueue(DiaryTask("S:1700000000000:task", "S", null, "delta"))
        val first = store.leaseNext("S", 10, 10)!!
        store.recoverExpired(20)
        val second = store.leaseNext("S", 20, 100)!!
        assertTrue(!store.completeWithCheckpointIfOwned(first.id, first.leaseToken!!, DiaryCheckpoint("old", 1, "n-old")))
        assertEquals(null, store.readCheckpoint("S"))
        assertTrue(store.completeWithCheckpointIfOwned(second.id, second.leaseToken!!, DiaryCheckpoint("new", 2, "n-new")))
        store.close()
    }

    @Test
    fun `stale lease cannot fail a reclaimed task`() = runTest {
        val store = SqlDelightDiaryTaskStore.open(dbPath)
        store.enqueue(DiaryTask("S:1700000000000:task", "S", null, "delta"))
        val first = store.leaseNext("S", 10, 10)!!
        store.recoverExpired(20)
        val second = store.leaseNext("S", 20, 100)!!
        store.fail(first.id, first.leaseToken!!, 21, "stale")
        assertEquals(DiaryTaskStatus.RUNNING, store.readById(second.id)?.status)
        store.close()
    }

    private inline fun <T> SqlDelightDiaryTaskStore.use(block: (SqlDelightDiaryTaskStore) -> T): T =
        try { block(this) } finally { close() }
}
