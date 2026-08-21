package io.openeden.cli.state

import io.openeden.client.ConversationHistoryPage
import io.openeden.client.ConversationTurn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CliReducerTest {
    @Test
    fun `initial history maps turns to complete chronological message pairs`() {
        val state = CliUiState.initial("local")
            .reduce(CliEvent.HistoryLoading)
            .reduce(
                CliEvent.HistoryLoaded(
                    page = historyPage(turn("t1"), turn("t2"), before = "older", hasMore = true),
                    initial = true,
                ),
            )

        assertEquals(
            listOf("t1:user", "t1:assistant", "t2:user", "t2:assistant"),
            state.messages.map { it.id },
        )
        assertEquals(
            listOf(CliRole.USER, CliRole.ASSISTANT, CliRole.USER, CliRole.ASSISTANT),
            state.messages.map { it.role },
        )
        assertEquals(listOf("user-t1", "assistant-t1", "user-t2", "assistant-t2"), state.messages.map { it.markdown })
        assertTrue(state.messages.all { it.status == CliMessageStatus.COMPLETE })
        assertEquals("older", state.historyBefore)
        assertFalse(state.historyLoading)
        assertFalse(state.historyExhausted)
    }

    @Test
    fun `history marks exhaustion when page has no cursor or no more turns`() {
        val missingCursor = CliUiState.initial("local").reduce(
            CliEvent.HistoryLoaded(historyPage(turn("t1"), before = null, hasMore = true), initial = true),
        )
        val noMore = CliUiState.initial("local").reduce(
            CliEvent.HistoryLoaded(historyPage(turn("t1"), before = "older", hasMore = false), initial = true),
        )

        assertTrue(missingCursor.historyExhausted)
        assertTrue(noMore.historyExhausted)
    }

    @Test
    fun `older history prepends missing pairs without replacing existing or streaming messages`() {
        val existing = listOf(
            historyMessage("t2", CliRole.USER, "existing-user-t2"),
            historyMessage("t2", CliRole.ASSISTANT, "existing-assistant-t2"),
            historyMessage("t3", CliRole.USER, "existing-user-t3"),
            historyMessage("t3", CliRole.ASSISTANT, "existing-assistant-t3"),
            CliMessage("local", CliRole.USER, "local", CliMessageStatus.COMPLETE),
            CliMessage("local:assistant", CliRole.ASSISTANT, "partial", CliMessageStatus.STREAMING),
        )
        val state = CliUiState(sessionId = "CLI:local", messages = existing).reduce(
            CliEvent.HistoryLoaded(
                historyPage(turn("t1"), turn("t2"), before = null, hasMore = false),
                initial = false,
            ),
        )

        assertEquals(
            listOf(
                "t1:user", "t1:assistant", "t2:user", "t2:assistant",
                "t3:user", "t3:assistant", "local", "local:assistant",
            ),
            state.messages.map { it.id },
        )
        assertEquals("existing-user-t2", state.messages[2].markdown)
        assertEquals("existing-assistant-t2", state.messages[3].markdown)
        assertEquals(CliMessageStatus.STREAMING, state.messages.last().status)
    }

    @Test
    fun `initial history retains explicit existing messages after hydrated history`() {
        val state = CliUiState(
            sessionId = "CLI:local",
            messages = listOf(
                historyMessage("stale", CliRole.USER, "stale-user"),
                historyMessage("stale", CliRole.ASSISTANT, "stale-assistant"),
                CliMessage("local:user", CliRole.USER, "local", CliMessageStatus.COMPLETE),
                CliMessage("local:assistant", CliRole.ASSISTANT, "partial", CliMessageStatus.STREAMING),
            ),
            requestActive = true,
        ).reduce(
            CliEvent.HistoryLoaded(
                historyPage(turn("t1"), before = null, hasMore = false),
                initial = true,
            ),
        )

        assertEquals(
            listOf(
                "t1:user", "t1:assistant", "stale:user", "stale:assistant",
                "local:user", "local:assistant",
            ),
            state.messages.map { it.id },
        )
        assertEquals(CliMessageStatus.STREAMING, state.messages.last().status)
    }

    @Test
    fun `initial history preserves a locally completed turn created while loading`() {
        val completed = CliUiState.initial("local")
            .reduce(CliEvent.HistoryLoading)
            .reduce(CliEvent.Submitted(text = "local question", id = "local-turn"))
            .reduce(CliEvent.RequestAccepted("local-turn"))
            .reduce(CliEvent.ResponseDelta("local partial"))
            .reduce(CliEvent.RequestCompleted("local response"))
        val localUser = completed.messages[0]
        val localAssistant = completed.messages[1]

        val hydrated = completed.reduce(
            CliEvent.HistoryLoaded(
                historyPage(turn("local-turn"), before = null, hasMore = false),
                initial = true,
            ),
        )

        assertEquals(
            listOf("local-turn:user", "local-turn:assistant"),
            hydrated.messages.map { it.id },
        )
        assertEquals(hydrated.messages.size, hydrated.messages.map { it.id }.distinct().size)
        assertSame(localAssistant, hydrated.messages[1])
        assertSame(localUser, hydrated.messages[0])
        assertEquals("local question", hydrated.messages[0].markdown)
        assertEquals("local response", hydrated.messages[1].markdown)
        assertFalse(hydrated.requestActive)
    }

    @Test
    fun `history loading clears notice and failure stops loading with fixed notice`() {
        val loading = CliUiState.initial("local")
            .reduce(CliEvent.Notice("old"))
            .reduce(CliEvent.HistoryLoading)
        val failed = loading.reduce(CliEvent.HistoryLoadFailed("Conversation history unavailable."))

        assertTrue(loading.historyLoading)
        assertNull(loading.notice)
        assertFalse(failed.historyLoading)
        assertEquals("Conversation history unavailable.", failed.notice)
    }

    @Test
    fun `submission and delta append vertically ordered user and provisional assistant messages`() {
        val state = CliUiState.initial("local")
            .reduce(
                CliEvent.Submitted(
                    text = "\u4F60\u597D",
                    id = "turn-1",
                    inlineTerminalCommitted = true,
                ),
            )
            .reduce(CliEvent.ResponseDelta("\u56DE\u7B54"))

        assertEquals("CLI:local", state.sessionId)
        assertEquals(listOf("turn-1:user", "turn-1:assistant"), state.messages.map { it.id })
        assertEquals(listOf(CliRole.USER, CliRole.ASSISTANT), state.messages.map { it.role })
        assertTrue(state.messages.first().inlineTerminalCommitted)
        assertFalse(state.messages.last().inlineTerminalCommitted)
        assertEquals("\u56DE\u7B54", state.messages.last().markdown)
        assertTrue(state.messages.last().provisional)
        assertTrue(state.requestActive)
        assertEquals("preparing", state.stage)
    }

    @Test
    fun `interruption marks the current assistant and ends the request`() {
        val state = CliUiState.initial("local")
            .reduce(CliEvent.Submitted(text = "hello", id = "turn-1"))
            .reduce(CliEvent.ResponseDelta("partial"))
            .reduce(CliEvent.RequestInterrupted)

        assertEquals(CliMessageStatus.INTERRUPTED, state.messages.last().status)
        assertEquals("turn-1:assistant", state.messages.last().id)
        assertEquals("partial", state.messages.last().markdown)
        assertFalse(state.requestActive)
        assertNull(state.stage)
        assertEquals("Generation interrupted", state.notice)
    }

    @Test
    fun `failure marks the canonical assistant message`() {
        val state = CliUiState.initial("local")
            .reduce(CliEvent.Submitted(text = "hello", id = "turn-1"))
            .reduce(CliEvent.RequestFailed("failed"))

        assertEquals("turn-1:assistant", state.messages.last().id)
        assertEquals(CliMessageStatus.FAILED, state.messages.last().status)
    }

    @Test
    fun `mode selection preserves session and messages`() {
        val inline = CliUiState.initial("local")
            .reduce(CliEvent.Submitted(text = "hello", id = "turn-1"))

        val fullScreen = inline.reduce(CliEvent.ModeSelected(CliMode.FULL_SCREEN))

        assertEquals(CliMode.FULL_SCREEN, fullScreen.mode)
        assertEquals(inline.sessionId, fullScreen.sessionId)
        assertSame(inline.messages, fullScreen.messages)
    }

    @Test
    fun `initial state uses inline mode with diagnostics hidden`() {
        val state = CliUiState.initial("local")

        assertEquals("CLI:local", state.sessionId)
        assertEquals(CliMode.INLINE, state.mode)
        assertFalse(state.diagnosticsVisible)
        assertNull(state.diagnostics)
        assertEquals(80, state.columns)
        assertEquals(24, state.rows)
    }

    @Test
    fun `blank completion keeps accumulated delta while final response replaces it`() {
        val streaming = CliUiState.initial("local")
            .reduce(CliEvent.Submitted(text = "hello", id = "turn-1"))
            .reduce(CliEvent.ResponseDelta("partial"))

        val blankCompleted = streaming.reduce(CliEvent.RequestCompleted("  "))
        val finalCompleted = streaming.reduce(CliEvent.RequestCompleted("final"))

        assertEquals("partial", blankCompleted.messages.last().markdown)
        assertEquals("final", finalCompleted.messages.last().markdown)
        assertEquals(CliMessageStatus.COMPLETE, blankCompleted.messages.last().status)
        assertEquals(CliMessageStatus.COMPLETE, finalCompleted.messages.last().status)
        assertEquals("turn-1:assistant", blankCompleted.messages.last().id)
        assertEquals("turn-1:assistant", finalCompleted.messages.last().id)
        assertFalse(blankCompleted.requestActive)
        assertNull(blankCompleted.stage)
    }

    @Test
    fun `hiding diagnostics clears snapshot without changing visibility on load`() {
        val diagnostics = diagnostics()
        val loadedWhileHidden = CliUiState.initial("local")
            .reduce(CliEvent.DiagnosticsLoaded(diagnostics))

        assertFalse(loadedWhileHidden.diagnosticsVisible)
        assertSame(diagnostics, loadedWhileHidden.diagnostics)

        val hidden = loadedWhileHidden
            .reduce(CliEvent.DiagnosticsVisibilityChanged(true))
            .reduce(CliEvent.DiagnosticsVisibilityChanged(false))

        assertFalse(hidden.diagnosticsVisible)
        assertNull(hidden.diagnostics)
    }

    @Test
    fun `clear visible history preserves session mode and diagnostics visibility`() {
        val beforeClear = CliUiState.initial("local")
            .reduce(CliEvent.Submitted(text = "hello", id = "turn-1"))
            .reduce(CliEvent.ModeSelected(CliMode.FULL_SCREEN))
            .reduce(CliEvent.DiagnosticsVisibilityChanged(true))
            .copy(
                historyBefore = "older-cursor",
                historyLoading = true,
                historyExhausted = true,
            )
        val state = beforeClear.reduce(CliEvent.ClearVisibleHistory)

        assertTrue(state.messages.isEmpty())
        assertEquals("Visible conversation cleared", state.notice)
        assertEquals("CLI:local", state.sessionId)
        assertEquals(CliMode.FULL_SCREEN, state.mode)
        assertTrue(state.diagnosticsVisible)
        assertEquals(beforeClear.historyBefore, state.historyBefore)
        assertEquals(beforeClear.historyLoading, state.historyLoading)
        assertEquals(beforeClear.historyExhausted, state.historyExhausted)
    }

    @Test
    fun `stream completion events do not modify user or completed assistant messages`() {
        val messages = listOf(
            CliMessage("old-user", CliRole.USER, "question", CliMessageStatus.COMPLETE),
            CliMessage("old-assistant", CliRole.ASSISTANT, "answer", CliMessageStatus.COMPLETE),
        )
        val active = CliUiState(
            sessionId = "CLI:local",
            messages = messages,
            requestActive = true,
            stage = "generating",
        )

        val afterDelta = active.reduce(CliEvent.ResponseDelta("unexpected"))
        val afterFailure = active.reduce(CliEvent.RequestFailed("failed"))
        val afterInterruption = active.reduce(CliEvent.RequestInterrupted)

        assertSame(messages, afterDelta.messages)
        assertEquals(messages, afterDelta.messages)
        assertSame(messages, afterFailure.messages)
        assertEquals(messages, afterFailure.messages)
        assertSame(messages, afterInterruption.messages)
        assertEquals(messages, afterInterruption.messages)
        assertFalse(afterFailure.requestActive)
        assertNull(afterFailure.stage)
        assertEquals("failed", afterFailure.notice)
    }

    @Test
    fun `request metadata resize and notice events update their own fields`() {
        val state = CliUiState.initial("local")
            .reduce(CliEvent.RequestAccepted("request-1"))
            .reduce(CliEvent.StageChanged("generating"))
            .reduce(CliEvent.Resized(columns = 120, rows = 40))
            .reduce(CliEvent.Notice("connected"))

        assertEquals("generating", state.stage)
        assertEquals(120, state.columns)
        assertEquals(40, state.rows)
        assertEquals("connected", state.notice)
    }

    private fun diagnostics() = CliDiagnostics(
        vector = List(8) { 0.5f },
        omega = 0.2f,
        shockActive = false,
        shockIntensity = null,
        evolutionIndex = 3L,
        derivedDissonance = 0.1f,
    )

    private fun turn(id: String) = ConversationTurn(
        turnId = id,
        platform = "CLI",
        scopeId = "local",
        userId = "local",
        userText = "user-$id",
        assistantText = "assistant-$id",
        completedAtMs = 1L,
    )

    private fun historyPage(
        vararg turns: ConversationTurn,
        before: String?,
        hasMore: Boolean,
    ) = ConversationHistoryPage(turns.toList(), before, hasMore)

    private fun historyMessage(turnId: String, role: CliRole, markdown: String) = CliMessage(
        id = "$turnId:${role.name.lowercase()}",
        role = role,
        markdown = markdown,
        status = CliMessageStatus.COMPLETE,
    )
}
