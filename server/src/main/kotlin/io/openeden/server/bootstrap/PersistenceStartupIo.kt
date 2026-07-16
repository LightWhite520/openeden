package io.openeden.server.bootstrap

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class PersistenceStartupIo(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun <T> open(block: () -> T): T = withContext(dispatcher) { block() }
}
