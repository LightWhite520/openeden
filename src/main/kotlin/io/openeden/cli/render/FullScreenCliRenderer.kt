package io.openeden.cli.render

import io.openeden.cli.state.CliUiState
import io.openeden.cli.state.CliMessage

interface FullscreenSink {
    fun capabilitiesAvailable(): Boolean
    fun enter(): Boolean
    fun write(changes: List<RowChange>)
    fun close()
}

class FullScreenCliRenderer(
    private val sink: FullscreenSink,
    private val inline: InlineCliRenderer = InlineCliRenderer(),
) : CliRenderer {
    private var previousRows: List<String> = emptyList()
    private var previousMessages: List<CliMessage> = emptyList()
    private var previousConversationWidth: Int? = null
    private var previousHistoryLoading = false
    private var viewportStartRow: Int? = null
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
        val conversationWidth = size.columns - 22
        val messageState = current.copy(
            requestActive = false,
            stage = null,
            diagnosticsVisible = false,
            notice = null,
        )
        val messageRows = inline.rows(messageState, conversationWidth)
        val conversation = buildList {
            addAll(messageRows)
            current.notice?.let { add("[notice] $it") }
        }
        val rail = "session ${current.sessionId}"
        val diagnostics = if (current.diagnosticsVisible) current.diagnostics?.let {
            listOf("diagnostics", "omega=${it.omega} shock=${it.shockActive}", "evolution=${it.evolutionIndex} D=${it.derivedDissonance}")
        }.orEmpty() else emptyList()
        val viewportHeight = (size.rows - 2 - diagnostics.size - 4).coerceAtLeast(1)
        val maxStart = (conversation.size - viewportHeight).coerceAtLeast(0)
        val purePrependCount = purePrependCount(current.messages, conversationWidth)
        val start = when {
            viewportStartRow == null -> maxStart
            purePrependCount != null -> {
                val insertedRows = inline.rows(
                    messageState.copy(messages = current.messages.take(purePrependCount)),
                    conversationWidth,
                ).size
                viewportStartRow!! + insertedRows
            }
            previousMessages != current.messages -> maxStart
            !previousHistoryLoading && current.historyLoading -> {
                (viewportStartRow!! - viewportHeight).coerceAtLeast(0)
            }
            else -> viewportStartRow!!
        }.coerceIn(0, maxStart)
        viewportStartRow = start
        val viewport = conversation.drop(start).take(viewportHeight)
        val rows = listOf("OpenEden  ${current.sessionId}", "┌ $rail ┐") + viewport.map { "│ $it" } + diagnostics +
            listOf("─".repeat(size.columns.coerceAtMost(96)), current.stage?.let { "[$it]" } ?: "Ready", "> ", "editor: active=${current.requestActive}")
        sink.write(FrameDiff.between(previousRows, rows))
        previousRows = rows
        previousMessages = current.messages
        previousConversationWidth = conversationWidth
        previousHistoryLoading = current.historyLoading
        return RenderDecision.Rendered
    }

    override fun close() {
        if (closed) return
        closed = true
        sink.close()
    }

    private fun purePrependCount(currentMessages: List<CliMessage>, width: Int): Int? {
        if (previousConversationWidth != width || currentMessages.size < previousMessages.size) return null
        val insertedCount = currentMessages.size - previousMessages.size
        if (insertedCount == 0) return null
        val retained = currentMessages.drop(insertedCount)
        return insertedCount.takeIf {
            retained.indices.all { index ->
                retained[index].id == previousMessages[index].id && retained[index] == previousMessages[index]
            }
        }
    }

    private fun fallback(notice: String): RenderDecision {
        if (entered) {
            sink.close()
            entered = false
            previousRows = emptyList()
        }
        return RenderDecision.FallbackToInline(notice)
    }
}
