package io.openeden.context

import io.openeden.prompt.BuiltPrompt
import kotlin.test.Test
import kotlin.test.assertTrue

class OpenAiTokenCounterTest {
    @Test
    fun `counts prompt text with the OpenAI o200k tokenizer`() {
        val counter = OpenAiTokenCounter()

        val count = counter.count(BuiltPrompt("system", "persona", "你好，ATRI"))

        assertTrue(count > 0)
    }
}
