package io.openeden.runtime.inference

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class JvmInferenceExecutor private constructor(
    private val dispatcher: CoroutineDispatcher,
    private val ownsDispatcher: Boolean,
) : InferenceExecutor, AutoCloseable {
    constructor() : this(createOwnedDispatcher(), true)

    constructor(dispatcher: CoroutineDispatcher) : this(dispatcher, false)

    private val closed = AtomicBoolean(false)

    override suspend fun <T> run(block: suspend () -> T): T =
        withContext(dispatcher) { block() }

    override fun close() {
        if (closed.compareAndSet(false, true) && ownsDispatcher) {
            (dispatcher as ExecutorCoroutineDispatcher).close()
        }
    }

    private companion object {
        const val DEFAULT_THREAD_COUNT = 2
        val nextThreadId = AtomicInteger()

        fun createOwnedDispatcher(): ExecutorCoroutineDispatcher =
            Executors.newFixedThreadPool(DEFAULT_THREAD_COUNT) { task ->
                Thread(task, "openeden-inference-${nextThreadId.incrementAndGet()}")
            }.asCoroutineDispatcher()
    }
}
