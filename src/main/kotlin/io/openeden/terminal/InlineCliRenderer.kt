package io.openeden.terminal

import org.jline.reader.LineReader

fun interface InlineHistorySink { fun printAbove(text: String) }

class InlineCliRenderer(
    private val history: InlineHistorySink? = null,
    private val markdown: MarkdownTextRenderer = MarkdownTextRenderer(),
) : CliRenderer {
    private val committed = mutableSetOf<String>()
    fun rows(state: CliUiState, width: Int): List<String> {
        val max = width.coerceAtMost(96).coerceAtLeast(1)
        return state.messages.flatMap { message ->
            val prefix = if (message.role == CliRole.USER) "> " else "ATRI: "
            markdown.render(message.markdown, (max - DisplayWidth.of(prefix)).coerceAtLeast(1)).lines().map { prefix + it }
        } + listOfNotNull(state.stage?.let { "[status] $it" }, state.notice?.let { "[notice] $it" })
    }
    override fun render(previous: CliUiState?, current: CliUiState, size: Size): RenderDecision {
        current.messages.filter { it.status == CliMessageStatus.COMPLETE && committed.add(it.id) }.forEach { msg -> history?.printAbove(rows(current.copy(messages = listOf(msg)), size.columns).joinToString("\n")) }
        return RenderDecision.Rendered
    }
    override fun close() = Unit
}
