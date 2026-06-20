package io.openeden.runtime

interface InferenceExecutor {
    suspend fun <T> run(block: suspend () -> T): T
}

object DirectInferenceExecutor : InferenceExecutor {
    override suspend fun <T> run(block: suspend () -> T): T = block()
}

class RecordingInferenceExecutor(
    private val delegate: InferenceExecutor = DirectInferenceExecutor,
) : InferenceExecutor {
    var calls: Int = 0
        private set

    override suspend fun <T> run(block: suspend () -> T): T {
        calls += 1
        return delegate.run(block)
    }
}
