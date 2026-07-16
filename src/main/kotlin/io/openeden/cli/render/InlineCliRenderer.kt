package io.openeden.cli.render

import io.openeden.cli.state.CliMessageStatus
import io.openeden.cli.state.CliRole
import io.openeden.cli.state.CliUiState

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
) : CliRenderer {
    private val committed = mutableSetOf<String>()
    fun rows(state: CliUiState, width: Int): List<String> {
        val max = width.coerceAtMost(96).coerceAtLeast(1)
        return state.messages.flatMap { message ->
            val prefix = if (message.role == CliRole.USER) "> " else " ATRI:"
            markdown.render(message.markdown, (max - DisplayWidth.of(prefix)).coerceAtLeast(1)).lines()
                .map { prefix + it }
        } + listOfNotNull(
            state.stage?.let { " [status] $it" },
            state.notice?.let { " [notice] $it" },
            if (state.diagnosticsVisible) state.diagnostics?.let {
                " [diagnostics] omega=${it.omega} shock=${it.shockActive} evolution=${it.evolutionIndex} D=${it.derivedDissonance}"
            } else null,
        )
    }

    fun activeRows(state: CliUiState, width: Int): List<String> = rows(
        state.copy(messages = state.messages.filter { it.status == CliMessageStatus.STREAMING }),
        width,
    )

    override fun render(previous: CliUiState?, current: CliUiState, size: Size): RenderDecision {
        current.messages.filter { it.status == CliMessageStatus.COMPLETE && committed.add(it.id) }.forEach { msg ->
            val committedState = current.copy(
                messages = listOf(msg),
                requestActive = false,
                stage = null,
                notice = null,
                diagnosticsVisible = false,
            )
            history?.printAbove(rows(committedState, size.columns).joinToString("\n"))
        }
        val provisional = current.messages.filter { it.status == CliMessageStatus.STREAMING }
        if (provisional.isNotEmpty() || current.requestActive || current.notice != null) {
            if (active != null) {
                active.render(activeRows(current, size.columns))
            } else if (current.notice != null && current.notice != previous?.notice) {
                history?.printAbove("[notice] ${current.notice}")
            }
        } else {
            active?.clear()
        }
        return RenderDecision.Rendered
    }

    override fun close() {
        active?.close()
    }
}
