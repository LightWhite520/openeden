package io.openeden.cli.render

import io.openeden.cli.state.CliMessage
import io.openeden.cli.state.CliUiState
import java.util.ArrayDeque
import java.util.LinkedHashMap

interface FullscreenSink {
    fun capabilitiesAvailable(): Boolean
    fun enter(): Boolean
    fun write(changes: List<RowChange>)
    fun close()
}

internal fun interface FullScreenMessageRowRenderer {
    fun rows(message: CliMessage, width: Int): List<String>
}

class FullScreenCliRenderer internal constructor(
    private val sink: FullscreenSink,
    private val rowRenderer: FullScreenMessageRowRenderer,
) : CliRenderer {
    constructor(
        sink: FullscreenSink,
        inline: InlineCliRenderer = InlineCliRenderer(),
    ) : this(
        sink,
        FullScreenMessageRowRenderer { message, width ->
            inline.rows(CliUiState(sessionId = "", messages = listOf(message)), width)
        },
    )

    private val rowCache = object : LinkedHashMap<String, CachedMessageRows>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedMessageRows>): Boolean =
            size > MAX_CACHED_MESSAGES
    }
    private var previousRows: List<String> = emptyList()
    private var previousHistoryLoading = false
    private var previousMessageCount = 0
    private var previousLastMessage: CliMessage? = null
    private var anchor: ViewportAnchor? = null
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
        val rail = "session ${current.sessionId}"
        val diagnostics = if (current.diagnosticsVisible) current.diagnostics?.let {
            listOf(
                "diagnostics",
                "omega=${it.omega} shock=${it.shockActive}",
                "evolution=${it.evolutionIndex} D=${it.derivedDissonance}",
            )
        }.orEmpty() else emptyList()
        val viewportHeight = (size.rows - 2 - diagnostics.size - 4).coerceAtLeast(1)

        anchor = preserveAnchor(current.messages)
        var viewport = materializeViewport(
            messages = current.messages,
            width = conversationWidth,
            height = viewportHeight,
            notice = current.notice,
            requestedAnchor = anchor,
        )
        if (!previousHistoryLoading && current.historyLoading) {
            val top = viewport.firstOrNull { it.messageId != null }
            anchor = top?.let {
                moveUp(
                    messages = current.messages,
                    fromMessageIndex = it.messageIndex,
                    fromLineIndex = it.lineIndex,
                    rowsToMove = viewportHeight,
                    width = conversationWidth,
                )
            }
            viewport = materializeViewport(
                messages = current.messages,
                width = conversationWidth,
                height = viewportHeight,
                notice = current.notice,
                requestedAnchor = anchor,
            )
        }

        val rows = listOf("OpenEden  ${current.sessionId}", "┌ $rail ┐") +
            viewport.map { "│ ${it.text}" } +
            diagnostics +
            listOf(
                "─".repeat(size.columns.coerceAtMost(96)),
                current.stage?.let { "[$it]" } ?: "Ready",
                "> ",
                "editor: active=${current.requestActive}",
            )
        sink.write(FrameDiff.between(previousRows, rows))
        previousRows = rows
        previousHistoryLoading = current.historyLoading
        previousMessageCount = current.messages.size
        previousLastMessage = current.messages.lastOrNull()
        return RenderDecision.Rendered
    }

    override fun close() {
        if (closed) return
        closed = true
        rowCache.clear()
        sink.close()
    }

    private fun preserveAnchor(messages: List<CliMessage>): ViewportAnchor? {
        val current = anchor ?: return null
        if (messages.isEmpty()) return null
        val sizeDelta = messages.size - previousMessageCount
        val candidateIndex = when {
            sizeDelta > 0 -> current.messageIndex + sizeDelta
            sizeDelta == 0 -> current.messageIndex
            else -> return null
        }
        val candidate = messages.getOrNull(candidateIndex) ?: return null
        if (candidate.id != current.messageId || candidate != current.message) return null
        if (sizeDelta == 0 && messages.lastOrNull() != previousLastMessage) return null
        return current.copy(messageIndex = candidateIndex, message = candidate)
    }

    private fun materializeViewport(
        messages: List<CliMessage>,
        width: Int,
        height: Int,
        notice: String?,
        requestedAnchor: ViewportAnchor?,
    ): List<ConversationRow> = if (requestedAnchor == null) {
        materializeBottom(messages, width, height, notice)
    } else {
        materializeAnchored(messages, width, height, notice, requestedAnchor)
    }

    private fun materializeBottom(
        messages: List<CliMessage>,
        width: Int,
        height: Int,
        notice: String?,
    ): List<ConversationRow> {
        val visible = ArrayDeque<ConversationRow>(height)
        notice?.let { visible.addFirst(ConversationRow(null, 0, -1, "[notice] $it")) }
        var messageIndex = messages.lastIndex
        while (messageIndex >= 0 && visible.size < height) {
            val message = messages[messageIndex]
            val rows = rowsFor(message, width)
            var lineIndex = rows.lastIndex
            while (lineIndex >= 0 && visible.size < height) {
                visible.addFirst(ConversationRow(message.id, lineIndex, messageIndex, rows[lineIndex]))
                lineIndex -= 1
            }
            messageIndex -= 1
        }
        return visible.toList()
    }

    private fun materializeAnchored(
        messages: List<CliMessage>,
        width: Int,
        height: Int,
        notice: String?,
        requestedAnchor: ViewportAnchor,
    ): List<ConversationRow> {
        val index = requestedAnchor.messageIndex.coerceIn(0, messages.lastIndex)
        val firstMessage = messages[index]
        val firstRows = rowsFor(firstMessage, width)
        val firstLine = requestedAnchor.lineIndex.coerceIn(0, firstRows.lastIndex.coerceAtLeast(0))
        val visible = ArrayDeque<ConversationRow>(height)
        var messageIndex = index
        while (messageIndex < messages.size && visible.size < height) {
            val message = messages[messageIndex]
            val rows = rowsFor(message, width)
            var lineIndex = if (messageIndex == index) firstLine else 0
            while (lineIndex < rows.size && visible.size < height) {
                visible.addLast(ConversationRow(message.id, lineIndex, messageIndex, rows[lineIndex]))
                lineIndex += 1
            }
            messageIndex += 1
        }
        if (visible.size < height) {
            notice?.let { visible.addLast(ConversationRow(null, 0, -1, "[notice] $it")) }
        }
        messageIndex = index
        var lineIndex = firstLine - 1
        var rows = firstRows
        while (visible.size < height) {
            if (lineIndex >= 0) {
                val message = messages[messageIndex]
                visible.addFirst(ConversationRow(message.id, lineIndex, messageIndex, rows[lineIndex]))
                lineIndex -= 1
                continue
            }
            messageIndex -= 1
            if (messageIndex < 0) break
            rows = rowsFor(messages[messageIndex], width)
            lineIndex = rows.lastIndex
        }
        visible.firstOrNull { it.messageId != null }?.let { top ->
            val message = messages[top.messageIndex]
            anchor = ViewportAnchor(message.id, top.lineIndex, top.messageIndex, message)
        }
        return visible.toList()
    }

    private fun moveUp(
        messages: List<CliMessage>,
        fromMessageIndex: Int,
        fromLineIndex: Int,
        rowsToMove: Int,
        width: Int,
    ): ViewportAnchor {
        var messageIndex = fromMessageIndex
        var lineIndex = fromLineIndex
        var remaining = rowsToMove
        if (remaining <= lineIndex) {
            lineIndex -= remaining
            val message = messages[messageIndex]
            return ViewportAnchor(message.id, lineIndex, messageIndex, message)
        }
        remaining -= lineIndex
        messageIndex -= 1
        while (messageIndex >= 0) {
            val message = messages[messageIndex]
            val rowCount = rowsFor(message, width).size
            if (remaining <= rowCount) {
                return ViewportAnchor(message.id, rowCount - remaining, messageIndex, message)
            }
            remaining -= rowCount
            messageIndex -= 1
        }
        val first = messages.first()
        return ViewportAnchor(first.id, 0, 0, first)
    }

    private fun rowsFor(message: CliMessage, width: Int): List<String> {
        val cached = rowCache[message.id]
        if (cached != null && cached.message == message && cached.width == width) return cached.rows
        return rowRenderer.rows(message, width).also { rows ->
            rowCache[message.id] = CachedMessageRows(message, width, rows)
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

    private data class CachedMessageRows(
        val message: CliMessage,
        val width: Int,
        val rows: List<String>,
    )

    private data class ViewportAnchor(
        val messageId: String,
        val lineIndex: Int,
        val messageIndex: Int,
        val message: CliMessage,
    )

    private data class ConversationRow(
        val messageId: String?,
        val lineIndex: Int,
        val messageIndex: Int,
        val text: String,
    )

    private companion object {
        const val MAX_CACHED_MESSAGES = 512
    }
}
