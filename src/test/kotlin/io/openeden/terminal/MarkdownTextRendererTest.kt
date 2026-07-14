package io.openeden.terminal

import kotlin.test.Test
import kotlin.test.assertTrue

class MarkdownTextRendererTest {
    @Test fun `renders markdown constructs and wraps by display width`() {
        val text = MarkdownTextRenderer().render("# 标题\n\n- **粗体**\n> 引用\n[链接](https://example.com)\n```kotlin\nval x = 1\n```", 12)
        assertTrue(text.contains("标题")); assertTrue(text.contains("val x = 1")); assertTrue(text.lines().all { DisplayWidth.of(it) <= 12 })
    }
}
