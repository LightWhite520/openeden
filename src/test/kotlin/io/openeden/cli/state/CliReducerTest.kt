package io.openeden.cli.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CliReducerTest {
    @Test
    fun `submission and delta append vertically ordered user and provisional assistant messages`() {
        val state = CliUiState.initial("local")
            .reduce(CliEvent.Submitted(text = "\u4F60\u597D", id = "turn-1"))
            .reduce(CliEvent.ResponseDelta("\u56DE\u7B54"))

        assertEquals("CLI:local", state.sessionId)
        assertEquals(listOf(CliRole.USER, CliRole.ASSISTANT), state.messages.map { it.role })
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
        assertEquals("partial", state.messages.last().markdown)
        assertFalse(state.requestActive)
        assertNull(state.stage)
        assertEquals("Generation interrupted", state.notice)
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
        val state = CliUiState.initial("local")
            .reduce(CliEvent.Submitted(text = "hello", id = "turn-1"))
            .reduce(CliEvent.ModeSelected(CliMode.FULL_SCREEN))
            .reduce(CliEvent.DiagnosticsVisibilityChanged(true))
            .reduce(CliEvent.ClearVisibleHistory)

        assertTrue(state.messages.isEmpty())
        assertEquals("Visible conversation cleared", state.notice)
        assertEquals("CLI:local", state.sessionId)
        assertEquals(CliMode.FULL_SCREEN, state.mode)
        assertTrue(state.diagnosticsVisible)
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
}
