package io.openeden.runtime

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class JvmInferenceExecutor(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : InferenceExecutor {
    override suspend fun <T> run(block: suspend () -> T): T =
        withContext(dispatcher) { block() }
}
