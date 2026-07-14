package io.openeden.runtime.inference

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class InferenceExecutorTest {
    @Test
    fun `direct executor returns block result`() = runTest {
        val result = DirectInferenceExecutor.run { "ok" }

        assertEquals("ok", result)
    }

    @Test
    fun `recording executor counts inference boundary crossings`() = runTest {
        val executor = RecordingInferenceExecutor()

        val result = executor.run { 42 }

        assertEquals(42, result)
        assertEquals(1, executor.calls)
    }
}
