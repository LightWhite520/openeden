package io.openeden.cli.render

import io.openeden.cli.terminal.TerminalSession
import org.jline.utils.AttributedString
import org.jline.utils.Status

class JLineInlineActiveSink(
    session: TerminalSession,
) : InlineActiveSink {
    private val status: Status? = Status.getStatus(session.terminal)

    override fun render(lines: List<String>) {
        val current = status ?: return
        current.update(emptyList(), false)
        current.resize()
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
