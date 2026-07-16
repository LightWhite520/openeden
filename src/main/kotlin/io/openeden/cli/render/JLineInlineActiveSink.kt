package io.openeden.cli.render

import io.openeden.cli.terminal.TerminalSession

class JLineInlineActiveSink(
    private val replace: (List<String>) -> Unit,
) : InlineActiveSink {
    constructor(session: TerminalSession) : this(session::replaceInlineActivity)

    override fun render(lines: List<String>) = replace(lines)
}
