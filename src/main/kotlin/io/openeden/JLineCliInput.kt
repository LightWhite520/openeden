package io.openeden

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReader
import org.jline.reader.UserInterruptException

class JLineCliInput(
    private val lineReader: LineReader,
) : CliInput {
    override suspend fun readLine(): String? = withContext(Dispatchers.IO) {
        try {
            lineReader.readLine()
        } catch (_: EndOfFileException) {
            null
        } catch (_: UserInterruptException) {
            ""
        }
    }
}
