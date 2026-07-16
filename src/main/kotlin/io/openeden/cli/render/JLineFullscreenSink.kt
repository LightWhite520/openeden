package io.openeden.cli.render

import io.openeden.cli.terminal.TerminalSession

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

    override fun write(rows: List<String>, inputRow: Int) {
        if (!entered) return
        session.replaceFullScreenFrame(rows, inputRow)
    }

    override fun close() {
        if (!entered) return
        entered = false
        session.exitFullScreen()
    }
}
