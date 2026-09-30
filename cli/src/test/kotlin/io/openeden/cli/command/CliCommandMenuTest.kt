package io.openeden.cli.command

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliCommandMenuTest {
    @Test fun `slash opens candidates and typing filters selection`() {
        val menu = CliCommandMenu()
        menu.update("/", 1)
        assertEquals(8, menu.candidates.size)
        menu.move(-1)
        assertEquals("/exit", menu.selection())
        menu.update("/mo", 3)
        assertEquals(listOf("/mode", "/model"), menu.candidates.map { it.value })
        assertEquals("/mode ", menu.selection())
        menu.update("/mode ", 6)
        assertEquals(listOf("full", "inline"), menu.candidates.map { it.value })
        menu.move(1)
        assertEquals("/mode inline", menu.selection())
    }

    @Test fun `dismissal survives redraw but a changed query reopens the menu`() {
        val menu = CliCommandMenu()
        menu.update("/", 1)
        assertTrue(menu.dismiss())
        repeat(3) { menu.update("/", 1); assertTrue(menu.candidates.isEmpty()) }
        assertFalse(menu.dismiss())
        menu.update("/h", 2)
        assertEquals(listOf("/help", "/history"), menu.candidates.map { it.value })
    }

    @Test fun `ordinary chat multiline input and cursor editing do not activate menu`() {
        val menu = CliCommandMenu()
        for (text in listOf("hello /mode", " /mode", "/mode\n", "/unknown")) {
            menu.update(text, text.length)
            assertTrue(menu.candidates.isEmpty())
        }
        menu.update("/mode", 2)
        assertTrue(menu.candidates.isEmpty())
        menu.update("/mode full", 10)
        assertTrue(menu.candidates.isEmpty())
    }
}
