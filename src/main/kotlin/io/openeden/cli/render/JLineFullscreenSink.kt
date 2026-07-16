package io.openeden.cli.render

import io.openeden.cli.terminal.TerminalSession
import org.jline.utils.InfoCmp.Capability

class JLineFullscreenSink(
    private val session: TerminalSession,
) : FullscreenSink {
    private var entered = false

    override fun capabilitiesAvailable(): Boolean = true

    override fun enter(): Boolean {
        session.replaceInlineActivity(emptyList())
        entered = session.enterFullScreen()
        return entered
    }

    override fun write(changes: List<RowChange>) {
        if (!entered) return
        val terminal = session.terminal
        val writer = terminal.writer()
        changes.forEach { change ->
            terminal.puts(Capability.cursor_address, change.index, 0)
            terminal.puts(Capability.clr_eol)
            writer.print(change.text)
        }
        terminal.flush()
    }

    override fun close() {
        if (!entered) return
        entered = false
        session.exitFullScreen()
    }
}
