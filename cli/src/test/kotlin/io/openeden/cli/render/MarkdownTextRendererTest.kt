package io.openeden.cli.render

import org.jline.utils.AttributedString
import org.jline.utils.AttributedStyle
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class MarkdownTextRendererTest {
    @Test fun `renders markdown constructs and wraps by display width`() {
        val text = MarkdownTextRenderer().render("# 标题\n\n- **粗体**\n> 引用\n[链接](https://example.com)\n```kotlin\nval x = 1\n```", 12)
        assertTrue(text.contains("标题")); assertTrue(text.contains("val x = 1")); assertTrue(text.lines().all { DisplayWidth.of(it) <= 12 })
    }

    @Test fun `display width ignores combining marks and wraps wide glyphs`() {
        assertEquals(1, DisplayWidth.of("e\u0301"))
        assertEquals(2, DisplayWidth.of("界"))
        val lines = MarkdownTextRenderer().render("界界", 2).lines()
        assertTrue(lines.all { DisplayWidth.of(it) <= 2 })
        assertTrue(MarkdownTextRenderer().render("界", 1).lines().all { DisplayWidth.of(it) <= 1 })
    }

    @Test fun `color mode preserves markdown styles without changing visible content`() {
        val markdown = "**Bold** and `code`\n\n- **Item**"
        val plain = MarkdownTextRenderer().render(markdown, 40)
        val colored = MarkdownTextRenderer(colorEnabled = true).render(markdown, 40)
        val attributed = AttributedString.fromAnsi(colored)

        assertFalse(plain.contains('\u001b'))
        assertTrue(colored.contains('\u001b'))
        assertEquals(plain, attributed.toString())
        for (word in listOf("Bold", "code", "Item")) {
            assertNotEquals(AttributedStyle.DEFAULT, attributed.styleAt(attributed.toString().indexOf(word)))
        }
    }

    @Test fun `styled wrapping counts visible columns and preserves wide graphemes`() {
        val content = "界界\uD83D\uDC69\u200D\uD83D\uDCBB界e\u0301"
        val colored = MarkdownTextRenderer(colorEnabled = true).render("**$content**", 4)
        val lines = colored.lines().map(AttributedString::fromAnsi)

        assertEquals(content, lines.joinToString("") { it.toString() })
        assertTrue(lines.all { it.columnLength() <= 4 })
        assertTrue(lines.all { DisplayWidth.of(it.toString()) <= 4 })
        assertTrue(lines.any { it.toString().contains("\uD83D\uDC69\u200D\uD83D\uDCBB") })
        assertTrue(lines.filter { it.isNotEmpty() }.all { it.styleAt(0) == AttributedStyle.DEFAULT.bold() })
    }
}
