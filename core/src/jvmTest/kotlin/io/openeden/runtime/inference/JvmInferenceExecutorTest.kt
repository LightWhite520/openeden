package io.openeden.runtime.inference

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JvmInferenceExecutorTest {
    @Test
    fun `default executor runs on a dedicated inference thread`() = runBlocking {
        val executor = JvmInferenceExecutor()
        val threadName = try {
            executor.run { Thread.currentThread().name }
        } finally {
            executor.close()
        }

        assertTrue(
            threadName.startsWith("openeden-inference-"),
            "Expected a dedicated inference thread, but ran on $threadName",
        )
    }

    @Test
    fun `close terminates owned workers and is idempotent`() = runBlocking {
        val executor = JvmInferenceExecutor()
        val worker = executor.run { Thread.currentThread() }

        executor.close()
        executor.close()
        worker.join(5_000)

        assertFalse(worker.isAlive, "Owned inference worker remained alive after close")
    }

    @Test
    fun `close does not close an injected dispatcher`() = runBlocking {
        val dispatcher = Executors.newSingleThreadExecutor { task ->
            Thread(task, "caller-owned-inference")
        }.asCoroutineDispatcher()
        try {
            val executor = JvmInferenceExecutor(dispatcher)

            executor.close()
            executor.close()

            assertTrue(
                withContext(dispatcher) { Thread.currentThread().name }
                    .startsWith("caller-owned-inference"),
            )
        } finally {
            dispatcher.close()
        }
    }
}
