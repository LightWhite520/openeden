package io.openeden.terminal

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InlineCliRendererTest {
    @Test fun `messages are vertical and hide diagnostics`() {
        val state = CliUiState("s", messages = listOf(CliMessage("u", CliRole.USER, "你好", CliMessageStatus.COMPLETE), CliMessage("a", CliRole.ASSISTANT, "😀 hi", CliMessageStatus.COMPLETE)), diagnosticsVisible = false, diagnostics = CliDiagnostics(emptyList(), .4f, false, null, 1, .2f))
        val rows = InlineCliRenderer().rows(state, 20)
        assertTrue(rows.indexOfFirst { it.contains("你好") } < rows.indexOfFirst { it.contains("😀") })
        assertFalse(rows.any { it.contains("omega", true) || it.contains("vector", true) })
        assertTrue(rows.none { it.contains("你好") && it.contains("😀") })
    }

    @Test fun `visible diagnostics are explicit and separate from conversation`() {
        val state = CliUiState("s", diagnosticsVisible = true, diagnostics = CliDiagnostics(emptyList(), .4f, true, .7f, 2, .2f))
        assertTrue(InlineCliRenderer().rows(state, 80).any { it.contains("diagnostics") && it.contains("omega") })
    }

    @Test fun `streaming assistant refreshes active sink without committing`() {
        val frames = mutableListOf<List<String>>()
        val renderer = InlineCliRenderer(active = InlineActiveSink { frames += it })
        val state = CliUiState("s", requestActive = true, stage = "generating", messages = listOf(
            CliMessage("a", CliRole.ASSISTANT, "partial", CliMessageStatus.STREAMING),
        ))
        renderer.render(null, state, Size(80, 24))
        assertTrue(frames.single().any { it.contains("partial") })
    }
}
