package io.openeden

fun interface CliInput {
    suspend fun readLine(): String?
}
