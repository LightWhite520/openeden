package io.openeden

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

suspend fun main(): Unit = coroutineScope {
    launch {
        println("Welcome to OpenEden")
    }
}
