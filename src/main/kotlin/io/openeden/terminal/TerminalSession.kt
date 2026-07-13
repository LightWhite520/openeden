package io.openeden.terminal

import kotlinx.coroutines.flow.Flow
import org.jline.reader.LineReader
import org.jline.terminal.Terminal

interface TerminalSession : AutoCloseable {
    val terminal: Terminal
    val lineReader: LineReader

    fun events(): Flow<CliTerminalEvent>

    fun enterFullScreen(): Boolean

    fun exitFullScreen()

    fun redisplay()
}
