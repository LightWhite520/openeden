package io.openeden.cli.render

import io.openeden.cli.state.CliMessage
import io.openeden.cli.state.CliMessageStatus
import io.openeden.cli.state.CliRole
import io.openeden.cli.state.CliUiState

// Keep all visible IDs stable while bounding IDs retained after they leave the transcript.
private const val MAX_REMOVED_MESSAGE_IDS = 4_096

fun interface InlineHistorySink {
    fun printAbove(text: String)
}

fun interface InlineActiveSink : AutoCloseable {
    fun render(lines: List<String>)

    fun clear() = render(emptyList())

    override fun close() = clear()
}

class InlineCliRenderer(
    private val history: InlineHistorySink? = null,
    private val markdown: MarkdownTextRenderer = MarkdownTextRenderer(),
    private val active: InlineActiveSink? = null,
    private val theme: CliTheme = CliTheme(),
    private val separateMessages: Boolean = false,
) : CliRenderer {
    private val committed = CommittedMessageOwnership(MAX_REMOVED_MESSAGE_IDS)

    fun rows(state: CliUiState, width: Int): List<String> {
        return buildList {
            state.messages.forEach { addAll(messageRows(it, width)) }
            state.stage?.let { addAll(theme.wrap(theme.status("[status] $it"), width)) }
            state.notice?.let { addAll(theme.wrap(theme.notice("[notice] $it"), width)) }
            diagnosticsRow(state)?.let { addAll(theme.wrap(theme.muted(it), width)) }
        }
    }

    fun activeRows(state: CliUiState, width: Int): List<String> = buildList {
        state.stage?.let { addAll(theme.wrap(theme.status("[status] $it"), width)) }
        state.messages.filter { it.status == CliMessageStatus.STREAMING }.forEach {
            addAll(messageRows(it, width))
        }
        state.notice?.let { addAll(theme.wrap(theme.notice("[notice] $it"), width)) }
        diagnosticsRow(state)?.let { addAll(theme.wrap(theme.muted(it), width)) }
    }

    override fun render(previous: CliUiState?, current: CliUiState, size: Size): RenderDecision {
        val completedMessages = LinkedHashMap<String, CliMessage>()
        current.messages.forEach { message ->
            if (message.status == CliMessageStatus.COMPLETE) completedMessages.putIfAbsent(message.id, message)
        }
        val newlyCompleted = committed.newIds(completedMessages.keys.toList()).mapNotNull(completedMessages::get)
        val completedToPrint = newlyCompleted.filterNot { it.role == CliRole.USER && it.inlineTerminalCommitted }
        val previousHadActiveRows = previous?.let { state ->
            state.requestActive || state.notice != null || state.messages.any { it.status == CliMessageStatus.STREAMING }
        } == true
        if (completedToPrint.isNotEmpty() && previousHadActiveRows) active?.clear()
        completedToPrint.forEach { msg ->
            val committedState = current.copy(
                messages = listOf(msg),
                requestActive = false,
                stage = null,
                notice = null,
                diagnosticsVisible = false,
            )
            val text = rows(committedState, size.columns).joinToString("\n")
            history?.printAbove(if (separateMessages) "\n$text\n" else text)
        }
        val provisional = current.messages.filter { it.status == CliMessageStatus.STREAMING }
        if (provisional.isNotEmpty() || current.requestActive || current.notice != null || diagnosticsRow(current) != null) {
            if (active != null) {
                active.render(activeRows(current, size.columns))
            } else if (current.notice != null && current.notice != previous?.notice) {
                history?.printAbove(theme.wrap(theme.notice("[notice] ${current.notice}"), size.columns).joinToString("\n"))
            }
        } else {
            if (completedToPrint.isEmpty()) active?.clear()
        }
        return RenderDecision.Rendered
    }

    override fun close() {
        active?.close()
    }

    private fun messageRows(message: CliMessage, width: Int): List<String> {
        val max = width.coerceAtLeast(1)
        val prefix = if (message.role == CliRole.USER) "> " else "ATRI: "
        val continuation = " ".repeat(DisplayWidth.of(prefix))
        val minimumContentWidth = message.markdown.codePoints()
            .map(DisplayWidth::codePointWidth)
            .max()
            .orElse(1)
            .coerceAtLeast(1)
        val contentWidth = (max - DisplayWidth.of(prefix)).coerceAtLeast(minimumContentWidth)
        return markdown.render(message.markdown, contentWidth).lines().mapIndexed { index, line ->
            val label = if (index == 0) {
                if (message.role == CliRole.USER) theme.user(prefix) else theme.assistant(prefix)
            } else continuation
            label + line
        }
    }

    private fun diagnosticsRow(state: CliUiState): String? = if (state.diagnosticsVisible) {
        state.diagnostics?.let {
            "[diagnostics] omega=${it.omega} shock=${it.shockActive} evolution=${it.evolutionIndex} D=${it.derivedDissonance}"
        }
    } else {
        null
    }
}
