package io.openeden.cli.render

import io.openeden.cli.state.CliUiState

interface FullscreenSink {
    fun capabilitiesAvailable(): Boolean
    fun enter(): Boolean
    fun write(changes: List<RowChange>)
    fun close()
}

class FullScreenCliRenderer(private val sink: FullscreenSink, private val inline: InlineCliRenderer = InlineCliRenderer()) : CliRenderer {
    private var previousRows: List<String> = emptyList()
    private var entered = false
    private var closed = false
    fun render(current: CliUiState, size: Size): RenderDecision = render(null, current, size)
    override fun render(previous: CliUiState?, current: CliUiState, size: Size): RenderDecision {
        if (closed) return RenderDecision.FallbackToInline("Renderer is closed.")
        if (!sink.capabilitiesAvailable()) return fallback("Terminal does not support full-screen capabilities.")
        if (size.columns < 80 || size.rows < 24) return fallback("Terminal too small for full-screen mode.")
        if (!entered) {
            if (!sink.enter()) return fallback("Terminal does not support full-screen capabilities.")
            entered = true
        }
        val conversation = inline.rows(current, size.columns - 22)
        val rail = "session ${current.sessionId}"
        val diagnostics = if (current.diagnosticsVisible) current.diagnostics?.let {
            listOf("diagnostics", "omega=${it.omega} shock=${it.shockActive}", "evolution=${it.evolutionIndex} D=${it.derivedDissonance}")
        }.orEmpty() else emptyList()
        val viewport = conversation.take((size.rows - 2 - diagnostics.size - 4).coerceAtLeast(1))
        val rows = listOf("OpenEden  ${current.sessionId}", "┌ $rail ┐") + viewport.map { "│ $it" } + diagnostics +
            listOf("─".repeat(size.columns.coerceAtMost(96)), current.stage?.let { "[$it]" } ?: "Ready", "> ", "editor: active=${current.requestActive}")
        sink.write(FrameDiff.between(previousRows, rows)); previousRows = rows
        return RenderDecision.Rendered
    }
    override fun close() { if (closed) return; closed = true; sink.close() }

    private fun fallback(notice: String): RenderDecision {
        if (entered) {
            sink.close()
            entered = false
            previousRows = emptyList()
        }
        return RenderDecision.FallbackToInline(notice)
    }
}
