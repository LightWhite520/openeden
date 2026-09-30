package io.openeden.cli.command

internal class CliCommandMenu(private val parser: CliCommandParser = CliCommandParser()) {
    var candidates: List<CommandCandidate> = emptyList()
        private set
    var selectedIndex: Int = 0
        private set
    private var input = ""
    private var dismissedInput: String? = null

    fun update(text: String, cursor: Int) {
        if (text != input) dismissedInput = null
        val next = if (text != dismissedInput && cursor == text.codePointCount(0, text.length) &&
            '\n' !in text && '\r' !in text) parser.complete(text) else emptyList()
        if (text != input || next != candidates) selectedIndex = 0
        input = text
        candidates = next
    }

    fun move(delta: Int): Boolean {
        if (candidates.isEmpty()) return false
        selectedIndex = Math.floorMod(selectedIndex + delta, candidates.size)
        return true
    }

    fun selection(): String? {
        val candidate = candidates.getOrNull(selectedIndex) ?: return null
        val completed = if (candidate.value.startsWith('/')) candidate.value
            else input.takeWhile { !it.isWhitespace() } + " " + candidate.value
        return completed + if (parser.complete("$completed ").isNotEmpty()) " " else ""
    }

    fun dismiss(): Boolean {
        if (candidates.isEmpty()) return false
        dismissedInput = input
        candidates = emptyList()
        return true
    }
}
