package io.openeden.cli

import io.openeden.cli.terminal.CliTerminalEvent
import io.openeden.cli.terminal.TerminalSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.jline.reader.LineReader
import org.jline.terminal.Terminal
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MainTest {
    @Test
    fun `writer acquisition failure still closes interactive session exactly once`() = runTest {
        val session = FakeTerminalSession(writerFailure = IllegalStateException("writer failed"))

        val error = assertFailsWith<IllegalStateException> {
            runInteractive(session) { 0 }
        }

        assertEquals("writer failed", error.message)
        assertEquals(1, session.closeCalls)
    }

    @Test
    fun `interactive error output is written before session closes`() = runTest {
        val calls = mutableListOf<String>()
        val written = StringWriter()
        val session = FakeTerminalSession(
            writer = PrintWriter(written, true),
            onClose = { calls += "close:${written}" },
        )

        val exitCode = runInteractive(session) { output ->
            output("server error\n")
            1
        }

        assertEquals(1, exitCode)
        assertEquals(listOf("close:server error\n"), calls)
        assertEquals(1, session.closeCalls)
    }

    @Test
    fun `interactive repl failure closes session exactly once`() = runTest {
        val session = FakeTerminalSession()

        assertFailsWith<IllegalStateException> {
            runInteractive(session) { throw IllegalStateException("repl failed") }
        }

        assertEquals(1, session.closeCalls)
    }

    private class FakeTerminalSession(
        writer: PrintWriter = PrintWriter(StringWriter(), true),
        writerFailure: Throwable? = null,
        private val onClose: () -> Unit = {},
    ) : TerminalSession {
        override val terminal: Terminal = proxy<Terminal> { method ->
            if (method.name == "writer") writerFailure?.let { throw it }
            if (method.name == "writer") writer else defaultValue(method.returnType)
        }
        override val lineReader: LineReader = proxy { method -> defaultValue(method.returnType) }
        var closeCalls = 0
            private set

        override fun events(): Flow<CliTerminalEvent> = emptyFlow()
        override fun enterFullScreen() = false
        override fun exitFullScreen() = Unit
        override fun redisplay() = Unit
        override fun replaceInlineActivity(lines: List<String>) = Unit
        override fun replaceFullScreenFrame(rows: List<String>, inputRow: Int) = Unit
        override fun close() {
            closeCalls += 1
            onClose()
        }
    }

    private companion object {
        inline fun <reified T> proxy(crossinline invoke: (java.lang.reflect.Method) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
                invoke(method)
            } as T

        fun defaultValue(type: Class<*>): Any? = when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            java.lang.Character.TYPE -> '\u0000'
            else -> null
        }
    }
}
