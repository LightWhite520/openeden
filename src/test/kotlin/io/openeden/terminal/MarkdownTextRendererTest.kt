package io.openeden.terminal

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

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
}
