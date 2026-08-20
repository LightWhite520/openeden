package io.openeden.server.persistence.sqldelight

import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

internal fun newSqliteDispatcher(name: String): ExecutorCoroutineDispatcher =
    Executors.newSingleThreadExecutor { runnable -> Thread(runnable, name) }
        .asCoroutineDispatcher()
