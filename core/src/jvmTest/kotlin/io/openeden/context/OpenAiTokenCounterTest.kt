package io.openeden.context

import io.openeden.prompt.PromptSegmentKind
import io.openeden.prompt.testBuiltPrompt
import io.openeden.transcript.ConversationTurn
import io.openeden.transcript.PromptHistorySerializer
import io.openeden.transcript.PromptHistorySnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenAiTokenCounterTest {
    @Test
    fun `counts prompt text with the OpenAI o200k tokenizer`() {
        val counter = OpenAiTokenCounter()

        val count = counter.count(
            testBuiltPrompt(
                PromptSegmentKind.SYSTEM_CONTRACT to "system",
                PromptSegmentKind.PERSONA to "persona",
                PromptSegmentKind.BIO to "dynamic context",
                PromptSegmentKind.USER to "你好，ATRI",
            ),
        )
        val withoutContext = counter.count(
            testBuiltPrompt(
                PromptSegmentKind.SYSTEM_CONTRACT to "system",
                PromptSegmentKind.PERSONA to "persona",
                PromptSegmentKind.USER to "你好，ATRI",
            ),
        )

        assertTrue(count > withoutContext)
    }

    @Test
    fun `counts the exact mixed-role provider wire expansion`() {
        val historyItems = PromptHistorySerializer().createItems(
            listOf(
                ConversationTurn(
                    turnId = "history-turn",
                    incarnationId = "incarnation-a",
                    sessionId = "CLI:local",
                    platform = "CLI",
                    scopeId = "local",
                    userId = "user-1",
                    userText = "历史用户🙂\n第二行",
                    assistantText = "历史助手回答",
                    completedAtMs = 1L,
                ),
            ),
        )
        val prompt = testBuiltPrompt(
            PromptSegmentKind.SYSTEM_CONTRACT to "system",
            PromptSegmentKind.PERSONA to "persona",
            PromptSegmentKind.INCARNATION_ANCHOR to "incarnation",
            PromptSegmentKind.BIO to "bio",
            PromptSegmentKind.USER to "当前问题",
            promptHistory = PromptHistorySnapshot(
                mutableTail = historyItems,
                sourceTurnIds = setOf("history-turn"),
            ),
        )

        assertEquals(23, OpenAiTokenCounter().count(prompt))
    }
}
