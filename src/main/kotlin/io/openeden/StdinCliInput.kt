package io.openeden

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

class StdinCliInput : CliInput {
    private val reader = BufferedReader(InputStreamReader(System.`in`, Charsets.UTF_8))

    override suspend fun readLine(): String? = withContext(Dispatchers.IO) { reader.readLine() }
}
