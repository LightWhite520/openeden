package io.openeden.cli.render

import kotlin.test.Test
import kotlin.test.assertEquals

class JLineInlineActiveSinkTest {
    @Test
    fun `sink delegates complete activity frames to terminal ownership`() {
        val frames = mutableListOf<List<String>>()
        val sink = JLineInlineActiveSink(frames::add)

        sink.render(listOf("[status] generating", "ATRI: partial"))
        sink.clear()

        assertEquals(
            listOf(listOf("[status] generating", "ATRI: partial"), emptyList()),
            frames,
        )
    }
}
