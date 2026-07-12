package io.openeden.server

import io.openeden.runtime.DiaryTask
import io.openeden.runtime.DiaryTaskStatus
import io.openeden.server.db.SqlDelightDiaryTaskStore
import kotlinx.coroutines.test.runTest
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
        Files.list(tempDir).use { stream -> stream.forEach { Files.deleteIfExists(it) } }
        Files.deleteIfExists(tempDir)
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
}
