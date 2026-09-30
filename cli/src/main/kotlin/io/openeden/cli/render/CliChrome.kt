package io.openeden.cli.render

import io.openeden.cli.state.CliUiState
import org.jline.utils.AttributedString

class CliChrome(private val theme: CliTheme = CliTheme()) {
    fun welcome(sessionId: String, width: Int): String = listOf(
        "",
        theme.brand("  OpenEden connected."),
        theme.muted("  session ") + sessionId,
        theme.muted("  " + "\u2500".repeat((width - 2).coerceIn(0, 62))),
        "",
    ).joinToString("\n") { theme.fit(it, width) }

    fun header(state: CliUiState, width: Int): List<String> = listOf(
        columns(theme.brand("OpenEden"), theme.assistant("ATRI"), width),
        columns(theme.muted("session ${state.sessionId}"), theme.muted("Conversation"), width),
    )

    fun footer(state: CliUiState, width: Int): List<String> = listOf(
        theme.muted("\u2500".repeat(width.coerceAtLeast(1))),
        if (state.requestActive) theme.status("[${state.stage ?: "Working"}]") else theme.assistant("Ready"),
        theme.user("> "),
        columns(
            theme.muted("${state.messages.size} messages"),
            theme.muted(when {
                state.historyLoading -> "Loading history..."
                state.historyExhausted -> "No older history"
                else -> "Recent history"
            }),
            width,
        ),
    )

    private fun columns(left: String, right: String, width: Int): String {
        val rightWidth = AttributedString.fromAnsi(right).columnLength()
        val leftWidth = (width - rightWidth - 3).coerceAtLeast(1)
        val fittedLeft = theme.fit(left, leftWidth)
        val used = AttributedString.fromAnsi(fittedLeft).columnLength()
        return theme.fit(fittedLeft + " ".repeat((width - used - rightWidth).coerceAtLeast(1)) + right, width)
    }
}
