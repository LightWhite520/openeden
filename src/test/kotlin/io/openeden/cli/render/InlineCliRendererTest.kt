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

    @Test fun `active rows put status before streaming message at column zero`() {
        val state = CliUiState(
            sessionId = "s",
            requestActive = true,
            stage = "generating",
            notice = "still working",
            messages = listOf(CliMessage("a", CliRole.ASSISTANT, "你好", CliMessageStatus.STREAMING)),
        )

        assertEquals(
            listOf("[status] generating", "ATRI: 你好", "[notice] still working"),
            InlineCliRenderer().activeRows(state, 80),
        )
    }

    @Test fun `wrapped labels appear only on first row with exact continuation indent`() {
        val state = CliUiState(
            sessionId = "s",
            messages = listOf(
                CliMessage("u", CliRole.USER, "123456789", CliMessageStatus.COMPLETE),
                CliMessage("a", CliRole.ASSISTANT, "你好世界", CliMessageStatus.COMPLETE),
            ),
        )

        assertEquals(
            listOf("> 12345678", "  9", "ATRI: 你好", "      世界"),
            InlineCliRenderer().rows(state, 10),
        )
    }

    @Test fun `very narrow width keeps one label and does not fail`() {
        val rows = InlineCliRenderer().rows(
            CliUiState(
                sessionId = "s",
                messages = listOf(CliMessage("a", CliRole.ASSISTANT, "你好", CliMessageStatus.COMPLETE)),
            ),
            width = 1,
        )

        assertEquals(listOf("ATRI: 你", "      好"), rows)
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

    @Test
    fun `inline terminal owned user message is claimed without duplicate output`() {
        val history = mutableListOf<String>()
        val renderer = InlineCliRenderer(history = InlineHistorySink { history += it })
        val state = CliUiState(
            sessionId = "s",
            messages = listOf(
                CliMessage(
                    id = "turn:user",
                    role = CliRole.USER,
                    markdown = "你好",
                    status = CliMessageStatus.COMPLETE,
                    inlineTerminalCommitted = true,
                ),
            ),
        )

        renderer.render(null, state, Size(80, 24))
        renderer.render(state, state, Size(80, 24))

        assertEquals(emptyList(), history)
    }

    @Test
    fun `renderer owned user message is committed to inline history`() {
        val history = mutableListOf<String>()
        val renderer = InlineCliRenderer(history = InlineHistorySink { history += it })
        val state = CliUiState(
            sessionId = "s",
            messages = listOf(
                CliMessage(
                    id = "restored:user",
                    role = CliRole.USER,
                    markdown = "你好",
                    status = CliMessageStatus.COMPLETE,
                ),
            ),
        )

        renderer.render(null, state, Size(80, 24))

        assertEquals(listOf("> 你好"), history)
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

    @Test fun `active rows clear before completed history is printed`() {
        val calls = mutableListOf<String>()
        val renderer = InlineCliRenderer(
            history = InlineHistorySink { calls += "history" },
            active = object : InlineActiveSink {
                override fun render(lines: List<String>) = Unit
                override fun clear() {
                    calls += "clear"
                }
            },
        )
        val streaming = CliUiState(
            sessionId = "s",
            requestActive = true,
            messages = listOf(CliMessage("turn:assistant", CliRole.ASSISTANT, "partial", CliMessageStatus.STREAMING)),
        )
        val completed = streaming.copy(
            requestActive = false,
            messages = listOf(CliMessage("turn:assistant", CliRole.ASSISTANT, "done", CliMessageStatus.COMPLETE)),
        )

        renderer.render(streaming, completed, Size(80, 24))

        assertEquals(listOf("clear", "history"), calls)
    }

    @Test fun `completed id remains committed after leaving visible state`() {
        val history = mutableListOf<String>()
        val renderer = InlineCliRenderer(history = InlineHistorySink { history += it })
        val complete = CliUiState(
            sessionId = "s",
            messages = listOf(CliMessage("turn:assistant", CliRole.ASSISTANT, "done", CliMessageStatus.COMPLETE)),
        )
        val empty = CliUiState(sessionId = "s")

        renderer.render(null, complete, Size(80, 24))
        renderer.render(complete, complete, Size(80, 24))
        assertEquals(1, history.size)

        renderer.render(complete, empty, Size(80, 24))
        renderer.render(empty, complete, Size(80, 24))

        assertEquals(1, history.size)
    }

    @Test fun `more than ownership capacity stays stable across consecutive frames`() {
        var historyWrites = 0
        val renderer = InlineCliRenderer(history = InlineHistorySink { historyWrites += 1 })
        val messages = List(4_097) { index ->
            CliMessage("turn:$index", CliRole.ASSISTANT, "", CliMessageStatus.COMPLETE)
        }
        val state = CliUiState(sessionId = "s", messages = messages)

        renderer.render(null, state, Size(80, 24))
        assertEquals(messages.size, historyWrites)

        renderer.render(state, state, Size(80, 24))
        assertEquals(messages.size, historyWrites)
    }

    @Test fun `streaming and failed ids commit only after complete transition`() {
        val history = mutableListOf<String>()
        val renderer = InlineCliRenderer(history = InlineHistorySink { history += it })
        val streaming = CliMessage("turn", CliRole.ASSISTANT, "partial", CliMessageStatus.STREAMING)
        val failed = streaming.copy(status = CliMessageStatus.FAILED)
        val complete = streaming.copy(markdown = "done", status = CliMessageStatus.COMPLETE)

        renderer.render(null, CliUiState("s", messages = listOf(streaming)), Size(80, 24))
        renderer.render(null, CliUiState("s", messages = listOf(failed)), Size(80, 24))
        renderer.render(null, CliUiState("s", messages = listOf(complete)), Size(80, 24))
        renderer.render(null, CliUiState("s", messages = listOf(complete)), Size(80, 24))

        assertEquals(1, history.size)
        assertTrue(history.single().contains("done"))
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
