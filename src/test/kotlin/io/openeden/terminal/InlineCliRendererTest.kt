package io.openeden.terminal

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InlineCliRendererTest {
    @Test fun `messages are vertical and hide diagnostics`() {
        val state = CliUiState("s", messages = listOf(CliMessage("u", CliRole.USER, "你好", CliMessageStatus.COMPLETE), CliMessage("a", CliRole.ASSISTANT, "😀 hi", CliMessageStatus.COMPLETE)), diagnosticsVisible = true, diagnostics = CliDiagnostics(emptyList(), .4f, false, null, 1, .2f))
        val rows = InlineCliRenderer().rows(state, 20)
        assertTrue(rows.indexOfFirst { it.contains("你好") } < rows.indexOfFirst { it.contains("😀") })
        assertFalse(rows.any { it.contains("omega", true) || it.contains("vector", true) })
        assertTrue(rows.none { it.contains("你好") && it.contains("😀") })
    }
}
