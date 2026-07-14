package io.openeden

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliInputSelectionTest {
    @Test
    fun `interactive no-argument sessions use jline`() {
        assertTrue(CliInputSelection.shouldUseJLine(emptyList(), consoleAvailable = true))
    }

    @Test
    fun `one-shot and redirected sessions keep explicit stream input`() {
        assertFalse(CliInputSelection.shouldUseJLine(listOf("chat"), consoleAvailable = true))
        assertFalse(CliInputSelection.shouldUseJLine(emptyList(), consoleAvailable = false))
    }
}
