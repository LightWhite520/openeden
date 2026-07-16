package io.openeden.cli.render

import io.openeden.cli.terminal.TerminalSession
import org.jline.utils.InfoCmp.Capability
import org.jline.utils.Status

class JLineFullscreenSink(
    private val session: TerminalSession,
) : FullscreenSink {
    private var entered = false

    override fun capabilitiesAvailable(): Boolean = true

    override fun enter(): Boolean {
        Status.getExistingStatus(session.terminal).ifPresent { status ->
            status.hide()
            status.close()
        }
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
