package io.openeden.cli.terminal

import org.jline.utils.ScreenTerminal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScreenTerminalLinesTest {
    @Test
    fun `constructor uses columns then rows`() {
        val screen = ScreenTerminal(7, 3, true)

        assertEquals(7, screen.columns)
        assertEquals(3, screen.rows)
        assertEquals(3, screen.screenAndScrollbackLines().size)
    }

    @Test
    fun `active response erased by cursor movement is not retained`() {
        val screen = ScreenTerminal(40, 6, true)
        screen.write("[status] generating\r\nATRI: active\r\n>\u001B[1A\r\u001B[0K")

        assertFalse(screen.screenAndScrollbackLines().contains("ATRI: active"))
    }

    @Test
    fun `committed response survives later status updates`() {
        val screen = ScreenTerminal(40, 6, true)
        screen.write("ATRI: committed\r\n>\u001B[0K\r\n\u001B[0K\r\n\u001B[2A")

        assertTrue(screen.screenAndScrollbackLines().contains("ATRI: committed"))
    }

    @Test
    fun `history and wide cells decode to physical lines`() {
        val screen = ScreenTerminal(8, 2, true)
        screen.write("ATRI: 你\r\nATRI: 好\r\n")

        val lines = screen.screenAndScrollbackLines()
        assertTrue(lines.contains("ATRI: 你"))
        assertTrue(lines.contains("ATRI: 好"))
    }
}
