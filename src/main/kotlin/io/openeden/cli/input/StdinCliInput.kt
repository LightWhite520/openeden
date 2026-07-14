package io.openeden.cli.input

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.Reader

class StdinCliInput(reader: Reader) : CliInput {
    private val reader = reader as? BufferedReader ?: reader.buffered()

    override suspend fun readLine(): String? = withContext(Dispatchers.IO) { reader.readLine() }
}
