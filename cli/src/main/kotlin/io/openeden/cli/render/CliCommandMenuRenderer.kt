package io.openeden.cli.render

import io.openeden.cli.command.CommandCandidate

internal class CliCommandMenuRenderer(private val theme: CliTheme) {
    fun rows(candidates: List<CommandCandidate>, selectedIndex: Int, width: Int, height: Int): List<String> {
        if (candidates.isEmpty() || height < 2) return emptyList()
        val count = minOf(candidates.size, height - 1)
        val start = (selectedIndex - count + 1).coerceIn(0, candidates.size - count)
        return listOf(theme.fit(theme.muted("Commands  ${selectedIndex + 1}/${candidates.size}"), width)) +
            candidates.subList(start, start + count).mapIndexed { index, candidate ->
                val selected = start + index == selectedIndex
                val text = (if (selected) "> " else "  ") + candidate.value.padEnd(12) + "  " + candidate.description
                theme.fit(if (selected) theme.user(text) else theme.muted(text), width)
            }
    }
}
