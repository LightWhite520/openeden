package io.openeden.cli.render

import io.openeden.cli.state.CliDiagnostics
import io.openeden.cli.state.CliMessage
import io.openeden.cli.state.CliMessageStatus
import io.openeden.cli.state.CliRole
import io.openeden.cli.state.CliUiState

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
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

    @Test fun `committed history excludes transient status rows`() {
        val history = mutableListOf<String>()
        val renderer = InlineCliRenderer(history = InlineHistorySink { history += it })
        val state = CliUiState("s", requestActive = true, stage = "finalizing", messages = listOf(
            CliMessage("a", CliRole.ASSISTANT, "done", CliMessageStatus.COMPLETE),
        ))
        renderer.render(null, state, Size(80, 24))
        assertTrue(history.single().contains("done"))
        kotlin.test.assertFalse(history.single().contains("finalizing"))
    }

    @Test fun `active region excludes committed messages and clears after completion`() {
        val history = mutableListOf<String>()
        val active = RecordingActiveSink()
        val renderer = InlineCliRenderer(
            history = InlineHistorySink { history += it },
            active = active,
        )
        val streaming = CliUiState(
            sessionId = "s",
            requestActive = true,
            stage = "generating",
            messages = listOf(
                CliMessage("turn:user", CliRole.USER, "hello", CliMessageStatus.COMPLETE),
                CliMessage("turn:assistant", CliRole.ASSISTANT, "partial", CliMessageStatus.STREAMING),
            ),
        )

        renderer.render(null, streaming, Size(80, 24))
        renderer.render(
            streaming,
            streaming.copy(
                requestActive = false,
                stage = null,
                messages = streaming.messages.map { message ->
                    if (message.id == "turn:assistant") {
                        message.copy(markdown = "done", status = CliMessageStatus.COMPLETE)
                    } else {
                        message
                    }
                },
            ),
            Size(80, 24),
        )

        assertFalse(active.frames.single().any { it.contains("hello") })
        assertTrue(active.frames.single().any { it.contains("partial") })
        assertEquals(1, active.clearCalls)
        assertEquals(1, history.count { it.contains("hello") })
        assertEquals(1, history.count { it.contains("done") })
    }

    private class RecordingActiveSink : InlineActiveSink {
        val frames = mutableListOf<List<String>>()
        var clearCalls = 0

        override fun render(lines: List<String>) {
            frames += lines
        }

        override fun clear() {
            clearCalls += 1
        }
    }
}
