package io.openeden.cli.terminal

import kotlin.test.Test
import kotlin.test.assertEquals

class TerminalProviderSelectionTest {
    @Test
    fun `windows requires native jni provider`() {
        assertEquals("jni", TerminalProviderSelection.forOs("Windows 11"))
    }

    @Test
    fun `unix keeps jni then exec fallback`() {
        assertEquals("jni,exec", TerminalProviderSelection.forOs("Linux"))
        assertEquals("jni,exec", TerminalProviderSelection.forOs("Mac OS X"))
    }
}
