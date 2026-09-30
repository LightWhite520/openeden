package io.openeden.cli.render

import org.jline.terminal.Terminal
import org.jline.utils.AttributedString
import org.jline.utils.AttributedStyle
import org.jline.utils.InfoCmp.Capability

class CliTheme(val colorEnabled: Boolean = false) {
    fun brand(text: String): String = paint(text, AttributedStyle.BOLD.foreground(AttributedStyle.CYAN))

    fun user(text: String): String = paint(text, AttributedStyle.BOLD.foreground(AttributedStyle.CYAN))

    fun assistant(text: String): String = paint(text, AttributedStyle.BOLD.foreground(AttributedStyle.GREEN))

    fun muted(text: String): String = paint(text, AttributedStyle.DEFAULT.faint())

    fun status(text: String): String = paint(text, AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW))

    fun notice(text: String): String = paint(text, AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW))

    fun fit(text: String, width: Int): String {
        val columns = width.coerceAtLeast(1)
        val value = AttributedString.fromAnsi(text)
        if (value.columnLength() <= columns) return text
        if (columns <= 3) return encode(value.columnSubSequence(0, columns))
        return encode(value.columnSubSequence(0, columns - 3)) + muted("...")
    }

    fun wrap(text: String, width: Int): List<String> {
        val columns = width.coerceAtLeast(1)
        return AttributedString.fromAnsi(text).columnSplitLength(columns, false, false).map(::encode)
    }

    private fun paint(text: String, style: AttributedStyle): String =
        if (colorEnabled) AttributedString(text, style).toAnsi() else text

    private fun encode(text: AttributedString): String = if (colorEnabled) text.toAnsi() else text.toString()

    companion object {
        fun forTerminal(terminal: Terminal, environment: Map<String, String> = System.getenv()): CliTheme =
            CliTheme(
                colorEnabled = environment["NO_COLOR"].isNullOrEmpty() &&
                    !terminal.type.startsWith("dumb") &&
                    (terminal.getNumericCapability(Capability.max_colors) ?: 0) >= 8,
            )
    }
}
