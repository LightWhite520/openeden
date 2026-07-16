package io.openeden.cli.render

import org.jline.terminal.Size
import kotlin.test.Test
import kotlin.test.assertEquals

class JLineInlineActiveSinkTest {
    @Test
    fun `active status reserves the terminal final column`() {
        assertEquals(size(99, 30), activeStatusSize(size(100, 30)))
    }

    @Test
    fun `one column terminal keeps a valid status width`() {
        assertEquals(size(1, 30), activeStatusSize(size(1, 30)))
    }

    private fun size(columns: Int, rows: Int) = Size.of(columns, rows)
}
