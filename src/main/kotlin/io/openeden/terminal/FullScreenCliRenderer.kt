package io.openeden.terminal

interface FullscreenSink {
    fun capabilitiesAvailable(): Boolean
    fun enter()
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
        if (size.columns < 80 || size.rows < 24 || !sink.capabilitiesAvailable()) return RenderDecision.FallbackToInline("Terminal too small for full-screen mode.")
        if (!entered) { sink.enter(); entered = true }
        val conversation = inline.rows(current, size.columns)
        val viewport = conversation.take((size.rows - 4).coerceAtLeast(1))
        val rows = listOf("OpenEden  ${current.sessionId}") + viewport + listOf("─".repeat(size.columns.coerceAtMost(96)), current.stage?.let { "[$it]" } ?: "Ready", "> ")
        sink.write(FrameDiff.between(previousRows, rows)); previousRows = rows
        return RenderDecision.Rendered
    }
    override fun close() { if (closed) return; closed = true; sink.close() }
}
