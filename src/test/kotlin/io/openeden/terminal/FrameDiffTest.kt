package io.openeden.terminal

import kotlin.test.Test
import kotlin.test.assertEquals

class FrameDiffTest {
    @Test fun `returns only changed rows`() {
        assertEquals(listOf(RowChange(1, "changed")), FrameDiff.between(listOf("same", "old"), listOf("same", "changed")))
    }
    @Test fun `same frames are empty`() { assertEquals(emptyList(), FrameDiff.between(listOf("a"), listOf("a"))) }
    @Test fun `length changes clear removed rows`() { assertEquals(listOf(RowChange(1, "new"), RowChange(2, "")), FrameDiff.between(listOf("a", "b", "c"), listOf("a", "new"))) }
}
