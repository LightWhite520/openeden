package io.openeden.onebot.egress

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OneBotResponseSegmenterTest {
    private val segmenter = OneBotResponseSegmenter()

    @Test
    fun `keeps short replies as one message`() {
        val text = "嗯，我在听。"

        assertEquals(listOf(text), segmenter.segment(text))
    }

    @Test
    fun `splits natural paragraphs without losing their separators`() {
        val text = "我把刚才的事情想了一下。\n\n现在可以慢慢说，不用一次把所有细节都讲完。"

        val segments = segmenter.segment(text)

        assertEquals(2, segments.size)
        assertEquals(text, segments.joinToString(separator = ""))
    }

    @Test
    fun `returns at most three segments`() {
        val text = """
            第一部分先把背景交代清楚，这样后面的判断才有上下文。

            第二部分再看现在的状态，暂时没有必要急着下结论。

            第三部分是接下来的安排，我们可以一步一步验证。

            最后补充一个小细节，避免之后重复排查同一个问题。
        """.trimIndent()

        val segments = segmenter.segment(text)

        assertTrue(segments.size in 1..3)
        assertEquals(text, segments.joinToString(separator = ""))
    }

    @Test
    fun `does not split urls fenced code blocks lists or block quotes`() {
        val url = "https://example.com/a.b?q=one.two"
        val code = """
            ```kotlin
            val value = "one.two。three"
            println(value)
            ```
        """.trimIndent()
        val listItem = "- 列表项里有完整的一句话。它仍然应该作为一条内容保留。"
        val quote = "> 引用中的第一句话。\n> 引用中的第二句话。"
        val text = """
            相关链接是 $url，先记在这里。

            $code

            $listItem

            $quote

            这些内容看完以后，再继续讨论下一步怎么处理会更清楚。
        """.trimIndent()

        val segments = segmenter.segment(text)

        assertEquals(text, segments.joinToString(separator = ""))
        assertEquals(1, segments.count { it.contains(url) })
        assertEquals(1, segments.count { it.contains(code) })
        assertEquals(1, segments.count { it.contains(listItem) })
        assertEquals(1, segments.count { it.contains(quote) })
    }
}
