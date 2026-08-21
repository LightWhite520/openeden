package io.openeden.client

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SseEventParserTest {
    @Test
    fun `parses arbitrary byte chunks without splitting chinese text`() = runTest {
        val bytes = "event: response.delta\ndata: {\"text\":\"你好\"}\n\n".encodeToByteArray()

        for (split in 1 until bytes.size) {
            val events = SseEventParser()
                .parse(flowOf(bytes.copyOfRange(0, split), bytes.copyOfRange(split, bytes.size)))
                .toList()

            assertEquals(listOf(ChatStreamEvent.ResponseDelta("你好")), events)
        }
    }

    @Test
    fun `parses multi line frames in order`() = runTest {
        val bytes = """
            event: accepted
            data: {"requestId":"req_1"}

            event: stage
            data: {"stage":"generating"}

            event: completed
            data: {"requestId":"req_1","status":"completed"}

        """.trimIndent().encodeToByteArray()

        val events = SseEventParser().parse(flowOf(bytes)).toList()

        assertEquals(
            listOf(
                ChatStreamEvent.Accepted("req_1"),
                ChatStreamEvent.Stage("generating"),
                ChatStreamEvent.Completed("req_1", "completed"),
            ),
            events,
        )
    }

    @Test
    fun `preserves partial utf8 after consuming an earlier frame`() = runTest {
        val first = "event: accepted\ndata: {\"requestId\":\"req_1\"}\n\n".encodeToByteArray()
        val second = "event: response.delta\ndata: {\"text\":\"你好\"}\n\n".encodeToByteArray()
        val chineseStart = second.indexOfFirst { it.toInt() and 0x80 != 0 }

        val events = SseEventParser().parse(
            flowOf(
                first + second.copyOfRange(0, chineseStart + 1),
                second.copyOfRange(chineseStart + 1, second.size),
            ),
        ).toList()

        assertEquals(
            listOf(ChatStreamEvent.Accepted("req_1"), ChatStreamEvent.ResponseDelta("你好")),
            events,
        )
    }
}
