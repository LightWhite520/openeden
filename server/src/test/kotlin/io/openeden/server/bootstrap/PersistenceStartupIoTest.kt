package io.openeden.server.bootstrap

import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.runTest

class PersistenceStartupIoTest {
    @Test
    fun `blocking persistence opens run on the configured dispatcher`() = runTest {
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "persistence-startup-io")
        }
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val threadName = PersistenceStartupIo(dispatcher).open {
                Thread.currentThread().name
            }

            assertTrue(threadName.startsWith("persistence-startup-io"), threadName)
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }
}
