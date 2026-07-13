package io.openeden.terminal

import org.jline.reader.Candidate
import org.jline.reader.Completer
import org.jline.reader.LineReader
import org.jline.reader.ParsedLine

internal class CliCommandCompleter(
    private val parser: CliCommandParser,
) : Completer {
    override fun complete(
        reader: LineReader,
        line: ParsedLine,
        candidates: MutableList<Candidate>,
    ) {
        complete(line.line(), candidates)
    }

    fun complete(line: String, candidates: MutableList<Candidate>) {
        parser.complete(line).mapTo(candidates) { candidate ->
            Candidate(
                candidate.value,
                candidate.value,
                null,
                candidate.description,
                null,
                null,
                true,
            )
        }
    }
}
