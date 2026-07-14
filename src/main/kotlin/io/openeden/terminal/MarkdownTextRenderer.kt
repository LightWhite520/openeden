package io.openeden.terminal

import com.github.ajalt.mordant.input.InputEvent
import com.github.ajalt.mordant.input.MouseTracking
import com.github.ajalt.mordant.markdown.Markdown
import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.rendering.Size
import com.github.ajalt.mordant.rendering.Theme
import com.github.ajalt.mordant.terminal.PrintRequest
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalInfo
import com.github.ajalt.mordant.terminal.TerminalInterface
import kotlin.time.TimeMark

class MarkdownTextRenderer {
    fun render(markdown: String, width: Int): String {
        require(width > 0)
        val terminal = Terminal(
            ansiLevel = AnsiLevel.NONE,
            theme = Theme.Default,
            terminalInterface = StringTerminalInterface(width),
        )
        val rendered = terminal.render(Markdown(markdown).render(terminal, width))
        return rendered.lineSequence().flatMap { wrap(it, width) }.joinToString("\n")
    }

    private fun wrap(text: String, width: Int): List<String> {
        if (text.isEmpty()) return listOf("")
        val result = mutableListOf<String>(); var line = StringBuilder(); var used = 0
        for (cp in text.codePoints().toArray()) {
            val ch = String(Character.toChars(cp)); val w = DisplayWidth.codePointWidth(cp)
            if (w > width) {
                if (line.isNotEmpty()) { result += line.toString(); line = StringBuilder(); used = 0 }
                result += "?"
                continue
            }
            if (line.isNotEmpty() && used + w > width) { result += line.toString(); line = StringBuilder(); used = 0 }
            line.append(ch); used += w
        }
        if (line.isNotEmpty()) result += line.toString()
        return result
    }
}

object DisplayWidth {
    fun of(value: String): Int {
        var total = 0
        var index = 0
        while (index < value.length) {
            val first = value.codePointAt(index)
            val clusterStart = index
            index += Character.charCount(first)
            var clusterWidth = codePointWidth(first)
            while (index < value.length) {
                val next = value.codePointAt(index)
                if (next == 0x200D) {
                    index += Character.charCount(next)
                    if (index < value.length) {
                        val joined = value.codePointAt(index)
                        clusterWidth = maxOf(clusterWidth, codePointWidth(joined))
                        index += Character.charCount(joined)
                    }
                } else if (codePointWidth(next) == 0) {
                    index += Character.charCount(next)
                } else {
                    break
                }
            }
            if (index == clusterStart) index++
            total += clusterWidth
        }
        return total
    }
    fun codePointWidth(cp: Int): Int = when {
        cp in 0x300..0x36f || cp in 0x1ab0..0x1aff || cp in 0x1dc0..0x1dff || cp in 0xfe00..0xfe0f || cp in 0xe0100..0xe01ef -> 0
        cp == 0x200d || cp in 0x200b..0x200f -> 0
        cp == 0 -> 0
        cp in 0x1100..0x11ff || cp in 0x2e80..0xa4cf || cp in 0xac00..0xd7a3 || cp in 0xf900..0xfaff || cp in 0xff01..0xff60 || cp in 0x1f300..0x1faff -> 2
        Character.isISOControl(cp) -> 0
        else -> 1
    }
}

private class StringTerminalInterface(private val width: Int) : TerminalInterface {
    override fun info(ansiLevel: AnsiLevel?, hyperlinks: Boolean?, outputInteractive: Boolean?, inputInteractive: Boolean?) =
        TerminalInfo(AnsiLevel.NONE, false, false, false, false)

    override fun completePrintRequest(request: PrintRequest) = Unit
    override fun readLineOrNull(hideInput: Boolean): String? = error("Markdown renderer cannot read input")
    override fun getTerminalSize() = Size(width, Int.MAX_VALUE)
    override fun readInputEvent(timeout: TimeMark, mouseTracking: MouseTracking): InputEvent =
        error("Markdown renderer cannot read input events")
    override fun enterRawMode(mouseTracking: MouseTracking): AutoCloseable = AutoCloseable { }
    override fun shouldAutoUpdateSize(): Boolean = false
}
