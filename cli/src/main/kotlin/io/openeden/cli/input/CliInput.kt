package io.openeden.cli.input

fun interface CliInput {
    suspend fun readLine(): String?
}
