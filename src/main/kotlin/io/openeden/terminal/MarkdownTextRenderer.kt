package io.openeden.terminal

import com.github.ajalt.mordant.markdown.Markdown

class MarkdownTextRenderer {
    fun render(markdown: String, width: Int): String {
        require(width > 0)
        Markdown(markdown) // parse through Mordant's markdown implementation
        val plain = markdown.lineSequence().map { line ->
            when {
                line.trimStart().startsWith("```") -> line.trim()
                line.trimStart().startsWith(">") -> "> " + line.trimStart().removePrefix("> ").removePrefix(">")
                line.trimStart().matches(Regex("[-*+] \\S.*")) -> "• " + line.trimStart().drop(2)
                line.trimStart().matches(Regex("#{1,6} \\S.*")) -> line.trimStart().dropWhile { it == '#' }.trimStart()
                else -> line.replace(Regex("!?(\\[.*?])\\(.*?\\)"), "$1").replace("**", "").replace("__", "").replace("`", "")
            }
        }.toList()
        return plain.flatMap { wrap(it, width) }.joinToString("\n")
    }

    private fun wrap(text: String, width: Int): List<String> {
        if (text.isEmpty()) return listOf("")
        val result = mutableListOf<String>(); var line = StringBuilder(); var used = 0
        for (cp in text.codePoints().toArray()) {
            val ch = String(Character.toChars(cp)); val w = DisplayWidth.codePointWidth(cp)
            if (line.isNotEmpty() && used + w > width) { result += line.toString(); line = StringBuilder(); used = 0 }
            line.append(ch); used += w
        }
        if (line.isNotEmpty()) result += line.toString()
        return result
    }
}

object DisplayWidth {
    fun of(value: String): Int = value.codePoints().toArray().sumOf { codePointWidth(it) }
    fun codePointWidth(cp: Int): Int = when {
        cp == 0 -> 0
        cp in 0x1100..0x11ff || cp in 0x2e80..0xa4cf || cp in 0xac00..0xd7a3 || cp in 0xf900..0xfaff || cp in 0xff01..0xff60 || cp in 0x1f300..0x1faff -> 2
        Character.isISOControl(cp) -> 0
        else -> 1
    }
}
