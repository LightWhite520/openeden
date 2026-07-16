package io.openeden.cli.render

import io.openeden.cli.terminal.TerminalSession
import org.jline.terminal.Size
import org.jline.utils.AttributedString
import org.jline.utils.Status

class JLineInlineActiveSink(
    session: TerminalSession,
) : InlineActiveSink {
    private val terminal = session.terminal
    private val status: Status? = Status.getStatus(terminal)

    override fun render(lines: List<String>) {
        val current = status ?: return
        current.update(emptyList(), false)
        current.resize(activeStatusSize(terminal.size))
        current.update(lines.map(::AttributedString))
    }

    override fun clear() {
        status?.hide()
    }

    override fun close() {
        status?.hide()
        status?.close()
    }
}

internal fun activeStatusSize(physical: Size): Size = Size.of(
    (physical.columns - 1).coerceAtLeast(1),
    physical.rows,
)
