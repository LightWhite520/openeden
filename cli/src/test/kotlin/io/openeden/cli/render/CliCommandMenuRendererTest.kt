package io.openeden.cli.render

import io.openeden.cli.command.CliCommandParser
import org.jline.utils.AttributedString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliCommandMenuRendererTest {
    @Test fun `small menus keep selected command visible within available rows and columns`() {
        val candidates = CliCommandParser().complete("/")
        for (width in listOf(10, 40, 79)) {
            val rows = CliCommandMenuRenderer(CliTheme(colorEnabled = true))
                .rows(candidates, candidates.lastIndex, width, 3)
                .map(AttributedString::fromAnsi)
            assertEquals(3, rows.size)
            assertTrue(rows.all { it.columnLength() <= width })
            assertTrue(rows.last().toString().startsWith("> /exit"))
        }
    }

    @Test fun `selection stays identifiable without ANSI colors`() {
        val rows = CliCommandMenuRenderer(CliTheme()).rows(CliCommandParser().complete("/"), 3, 79, 8)
        assertEquals(8, rows.size)
        assertTrue(rows.none { '\u001b' in it })
        assertEquals(1, rows.count { it.startsWith("> ") })
        assertTrue(rows.single { it.startsWith("> ") }.contains("/mode"))
    }
}
