package io.openeden.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FullScreenCliRendererTest {
    @Test fun `falls back when terminal is too small`() {
        val renderer = FullScreenCliRenderer(FakeFullscreenSink(capable = true))
        assertEquals(RenderDecision.FallbackToInline("Terminal too small for full-screen mode."), renderer.render(CliUiState.initial("x"), Size(79, 24)))
    }
    @Test fun `capability failure has distinct fallback reason`() {
        val renderer = FullScreenCliRenderer(FakeFullscreenSink(capable = false))
        assertEquals(RenderDecision.FallbackToInline("Terminal does not support full-screen capabilities."), renderer.render(CliUiState.initial("x"), Size(100, 30)))
    }
    @Test fun `close is idempotent`() { val sink = FakeFullscreenSink(true); val renderer = FullScreenCliRenderer(sink); renderer.close(); renderer.close(); assertTrue(sink.closed) }

    @Test fun `rich layout includes session editor and visible diagnostics`() {
        val sink = FakeFullscreenSink(true)
        val renderer = FullScreenCliRenderer(sink)
        val state = CliUiState("CLI:local", requestActive = true, diagnosticsVisible = true,
            diagnostics = CliDiagnostics(emptyList(), .4f, true, .7f, 2, .2f))
        assertEquals(RenderDecision.Rendered, renderer.render(state, Size(100, 30)))
        val output = sink.changes.joinToString("\n") { it.text }
        assertTrue(output.contains("session CLI:local"))
        assertTrue(output.contains("editor: active=true"))
        assertTrue(output.contains("diagnostics"))
    }

    @Test fun `shrinking after entry exits fullscreen before inline fallback`() {
        val sink = FakeFullscreenSink(true)
        val renderer = FullScreenCliRenderer(sink)
        renderer.render(CliUiState.initial("x"), Size(100, 30))
        assertEquals(RenderDecision.FallbackToInline("Terminal too small for full-screen mode."), renderer.render(CliUiState.initial("x"), Size(79, 24)))
        assertTrue(sink.closed)
    }
}

private class FakeFullscreenSink(val capable: Boolean) : FullscreenSink {
    var closed = false
    val changes = mutableListOf<RowChange>()
    override fun capabilitiesAvailable() = capable
    override fun enter() = Unit
    override fun write(changes: List<RowChange>) { this.changes += changes }
    override fun close() { closed = true }
}
