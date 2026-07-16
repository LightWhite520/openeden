package io.openeden.cli.render

import io.openeden.cli.terminal.CliTerminalEvent
import io.openeden.cli.terminal.TerminalSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.jline.reader.LineReader
import org.jline.terminal.Terminal
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals

class JLineFullscreenSinkTest {
    @Test
    fun `enter clears inline activity before alternate screen`() {
        val calls = mutableListOf<String>()
        val sink = JLineFullscreenSink(FakeTerminalSession(calls))

        sink.enter()

        assertEquals(listOf("clear", "enter"), calls)
    }

    @Test
    fun `write delegates the complete frame to the terminal session`() {
        val calls = mutableListOf<String>()
        val sink = JLineFullscreenSink(FakeTerminalSession(calls))
        sink.enter()

        sink.write(listOf("OpenEden", "> ", "editor: active=false"), inputRow = 1)

        assertEquals(
            listOf("clear", "enter", "frame:1:OpenEden|> |editor: active=false"),
            calls,
        )
    }

    private class FakeTerminalSession(private val calls: MutableList<String>) : TerminalSession {
        override val terminal: Terminal = proxy()
        override val lineReader: LineReader = proxy()
        override fun events(): Flow<CliTerminalEvent> = emptyFlow()
        override fun enterFullScreen(): Boolean = true.also { calls += "enter" }
        override fun exitFullScreen() = Unit
        override fun redisplay() = Unit
        override fun replaceInlineActivity(lines: List<String>) {
            if (lines.isEmpty()) calls += "clear"
        }
        override fun replaceFullScreenFrame(rows: List<String>, inputRow: Int) {
            calls += "frame:$inputRow:${rows.joinToString("|")}"
        }
        override fun close() = Unit
    }

    private companion object {
        inline fun <reified T> proxy(): T = Proxy.newProxyInstance(
            T::class.java.classLoader,
            arrayOf(T::class.java),
        ) { _, method, _ ->
            when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                else -> null
            }
        } as T
    }
}
