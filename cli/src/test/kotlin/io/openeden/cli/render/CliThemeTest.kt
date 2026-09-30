package io.openeden.cli.render

import org.jline.terminal.Terminal
import org.jline.utils.AttributedString
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliThemeTest {
    @Test
    fun `terminal palette respects capabilities and NO_COLOR`() {
        val terminal = terminal("xterm-256color", 256)
        assertTrue(CliTheme.forTerminal(terminal, emptyMap()).brand("OpenEden").contains('\u001b'))
        assertEquals("OpenEden", CliTheme.forTerminal(terminal, mapOf("NO_COLOR" to "1")).brand("OpenEden"))
        assertEquals("OpenEden", CliTheme.forTerminal(terminal("dumb", 256), emptyMap()).brand("OpenEden"))
        assertEquals("OpenEden", CliTheme.forTerminal(terminal("vt100", null), emptyMap()).brand("OpenEden"))
    }

    @Test
    fun `colored CJK text clips by terminal columns and retains styles`() {
        val theme = CliTheme(colorEnabled = true)
        val clipped = AttributedString.fromAnsi(theme.fit(theme.brand("\u4f60\u597d\u4e16\u754cABC"), 8))
        assertEquals("\u4f60\u597d...", clipped.toString())
        assertTrue(clipped.columnLength() <= 8)
        assertFalse(clipped.styleAt(0).equals(org.jline.utils.AttributedStyle.DEFAULT))
    }

    @Test
    fun `multiline notices wrap without counting ANSI as visible cells`() {
        val theme = CliTheme(colorEnabled = true)
        val text = "0123456789\n\u4f60\u597d\u4e16\u754c"
        val rows = theme.wrap(theme.notice(text), 6).map(AttributedString::fromAnsi)
        assertTrue(rows.all { it.columnLength() <= 6 })
        assertEquals(text.replace("\n", ""), rows.joinToString("") { it.toString() })
        assertTrue(rows.filter { it.isNotEmpty() }.all { it.styleAt(0).style == rows.first().styleAt(0).style })
    }

    private fun terminal(type: String, colors: Int?): Terminal = Proxy.newProxyInstance(
        Terminal::class.java.classLoader,
        arrayOf(Terminal::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getType" -> type
            "getNumericCapability" -> colors
            else -> error("Unexpected terminal call: ${method.name}")
        }
    } as Terminal
}
