package io.openeden.server.persistence.sqldelight

import io.openeden.runtime.lifecycle.IncarnationLifecycle
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class SqlDelightIncarnationLifecycleStoreTest {
    private val tempDir = Files.createTempDirectory("openeden-lifecycle-test")
    private val dbPath = tempDir.resolve("openeden.db")

    @AfterTest
    fun cleanup() {
        Files.list(tempDir).use { stream -> stream.forEach { Files.deleteIfExists(it) } }
        Files.deleteIfExists(tempDir)
    }

    @Test
    fun `lifecycle transitions survive restart and fresh creation is idempotent`() = runTest {
        val store = SqlDelightIncarnationLifecycleStore.open(dbPath)
        val original = store.activeIncarnationId()
        try {
            assertEquals(IncarnationLifecycle.ACTIVE, store.read())
            assertEquals(IncarnationLifecycle.CRITICAL, store.markCritical())
            assertEquals(IncarnationLifecycle.TERMINATING, store.beginTermination())
            assertEquals(IncarnationLifecycle.TERMINATED, store.markTerminated())
        } finally {
            store.close()
        }

        val reopened = SqlDelightIncarnationLifecycleStore.open(dbPath)
        try {
            assertEquals(IncarnationLifecycle.TERMINATED, reopened.read())
            val fresh = reopened.createFresh("fresh-request", 2_000L)
            assertNotEquals(original, fresh)
            assertEquals(fresh, reopened.createFresh("fresh-request", 3_000L))
            assertEquals(IncarnationLifecycle.ACTIVE, reopened.read())
        } finally {
            reopened.close()
        }
    }

    @Test
    fun `illegal lifecycle transition is rejected`() = runTest {
        val store = SqlDelightIncarnationLifecycleStore.open(dbPath)
        try {
            assertFailsWith<IllegalStateException> { store.beginTermination() }
            assertEquals(IncarnationLifecycle.CRITICAL, store.markCritical())
            assertFailsWith<IllegalStateException> { store.markTerminated() }
        } finally {
            store.close()
        }
    }
}
