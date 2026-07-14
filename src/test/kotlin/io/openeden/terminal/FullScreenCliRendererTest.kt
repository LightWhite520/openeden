package io.openeden.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FullScreenCliRendererTest {
    @Test fun `falls back when terminal is too small`() {
        val renderer = FullScreenCliRenderer(FakeFullscreenSink(capable = true))
        assertEquals(RenderDecision.FallbackToInline("Terminal too small for full-screen mode."), renderer.render(CliUiState.initial("x"), Size(79, 24)))
    }
    @Test fun `close is idempotent`() { val sink = FakeFullscreenSink(true); val renderer = FullScreenCliRenderer(sink); renderer.close(); renderer.close(); assertTrue(sink.closed) }
}

private class FakeFullscreenSink(val capable: Boolean) : FullscreenSink {
    var closed = false
    override fun capabilitiesAvailable() = capable
    override fun enter() = Unit
    override fun write(changes: List<RowChange>) = Unit
    override fun close() { closed = true }
}
