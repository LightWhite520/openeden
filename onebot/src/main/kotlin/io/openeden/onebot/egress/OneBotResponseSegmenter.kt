package io.openeden.onebot.egress

/** Splits long responses at human-readable boundaries without breaking content blocks. */
class OneBotResponseSegmenter(
    private val maxSingleSegmentLength: Int = 240,
    private val maxSegments: Int = 3,
) {
    init {
        require(maxSingleSegmentLength > 0) { "maxSingleSegmentLength must be positive" }
        require(maxSegments > 0) { "maxSegments must be positive" }
    }

    fun segment(text: String): List<String> {
        if (text.isBlank()) return emptyList()

        val protected = protectedRanges(text)
        val paragraphBoundaries = paragraphBoundaries(text, protected)
        if (paragraphBoundaries.isNotEmpty()) {
            return split(text, selectBoundaries(paragraphBoundaries, text.length))
        }
        if (text.length <= maxSingleSegmentLength) return listOf(text)

        val sentenceBoundaries = sentenceBoundaries(text, protected)
        return if (sentenceBoundaries.isEmpty()) {
            listOf(text)
        } else {
            split(text, selectBoundaries(sentenceBoundaries, text.length))
        }
    }

    private fun selectBoundaries(boundaries: List<Int>, textLength: Int): List<Int> {
        if (boundaries.size <= maxSegments - 1) return boundaries
        val splitCount = maxSegments - 1
        return (1..splitCount)
            .map { slot -> boundaries[(boundaries.size * slot) / maxSegments] }
            .distinct()
            .filter { it in 1 until textLength }
    }

    private fun split(text: String, boundaries: List<Int>): List<String> {
        if (boundaries.isEmpty()) return listOf(text)
        val result = ArrayList<String>(boundaries.size + 1)
        var start = 0
        boundaries.forEach { boundary ->
            if (boundary > start) {
                result += text.substring(start, boundary)
                start = boundary
            }
        }
        if (start < text.length) result += text.substring(start)
        return result.ifEmpty { listOf(text) }
    }

    private fun paragraphBoundaries(text: String, protected: BooleanArray): List<Int> {
        val boundaries = ArrayList<Int>()
        var index = 0
        while (index < text.length - 1) {
            if (text[index] != '\n') {
                index++
                continue
            }
            var boundary = index + 1
            while (boundary < text.length && text[boundary] == '\n') boundary++
            while (boundary < text.length && (text[boundary] == ' ' || text[boundary] == '\t')) {
                boundary++
            }
            if (boundary > index + 1 && boundary <= text.length &&
                (index until boundary).none { protected[it] }
            ) {
                boundaries += boundary
            }
            index = boundary
        }
        return boundaries
    }

    private fun sentenceBoundaries(text: String, protected: BooleanArray): List<Int> {
        val boundaries = ArrayList<Int>()
        var index = 0
        while (index < text.length) {
            if (protected[index] || !isSentenceTerminator(text[index])) {
                index++
                continue
            }
            if (text[index] == '.' && index + 1 < text.length && !text[index + 1].isWhitespace()) {
                index++
                continue
            }
            var boundary = index + 1
            while (boundary < text.length && text[boundary] in CLOSING_PUNCTUATION) boundary++
            boundaries += boundary
            index = boundary
        }
        return boundaries
    }

    private fun isSentenceTerminator(char: Char): Boolean = char in SENTENCE_TERMINATORS || char == '.'

    private fun protectedRanges(text: String): BooleanArray {
        val protected = BooleanArray(text.length)
        val lines = lines(text)
        var fenceMarker: Char? = null
        var fenceStart = -1

        lines.forEach { line ->
            val trimmed = text.substring(line.start, line.contentEnd).trimStart()
            val marker = fenceMarker(trimmed)
            if (marker != null) {
                if (fenceMarker == null) {
                    fenceMarker = marker
                    fenceStart = line.start
                } else if (fenceMarker == marker) {
                    mark(protected, fenceStart, line.end)
                    fenceMarker = null
                    fenceStart = -1
                }
            }
            if (isListItem(trimmed) || trimmed.startsWith(">")) {
                mark(protected, line.start, line.end)
            }
        }
        if (fenceMarker != null) mark(protected, fenceStart, text.length)

        URL_PATTERN.findAll(text).forEach { match ->
            mark(protected, match.range.first, match.range.last + 1)
        }
        protectInlineCode(text, protected)
        return protected
    }

    private fun protectInlineCode(text: String, protected: BooleanArray) {
        var start = -1
        text.forEachIndexed { index, char ->
            if (char != '`') return@forEachIndexed
            if (start < 0) {
                start = index
            } else {
                mark(protected, start, index + 1)
                start = -1
            }
        }
        if (start >= 0) mark(protected, start, text.length)
    }

    private fun fenceMarker(line: String): Char? = when {
        line.startsWith("```") -> '`'
        line.startsWith("~~~") -> '~'
        else -> null
    }

    private fun isListItem(line: String): Boolean =
        line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ ") ||
            LIST_NUMBER_PATTERN.matches(line)

    private fun lines(text: String): List<Line> {
        val result = ArrayList<Line>()
        var start = 0
        while (start < text.length) {
            val newline = text.indexOf('\n', start)
            val end = if (newline >= 0) newline + 1 else text.length
            val contentEnd = if (newline >= 0) newline else text.length
            result += Line(start, end, contentEnd)
            start = end
        }
        if (text.isEmpty()) result += Line(0, 0, 0)
        return result
    }

    private fun mark(protected: BooleanArray, start: Int, endExclusive: Int) {
        for (index in start.coerceAtLeast(0) until endExclusive.coerceAtMost(protected.size)) {
            protected[index] = true
        }
    }

    private data class Line(val start: Int, val end: Int, val contentEnd: Int)

    private companion object {
        val SENTENCE_TERMINATORS = setOf('。', '！', '？', '!', '?', '；', ';')
        val CLOSING_PUNCTUATION = setOf('"', '\'', ')', ']', '}', '》', '」', '』', '”', '’')
        val URL_PATTERN = Regex("""https?://[^\s<>()]+""")
        val LIST_NUMBER_PATTERN = Regex("""\d+[.)]\s+.*""")
    }
}
