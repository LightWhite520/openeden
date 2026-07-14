package io.openeden.cli.render

data class RowChange(val index: Int, val text: String)

object FrameDiff {
    fun between(previousRows: List<String>, currentRows: List<String>): List<RowChange> {
        val max = maxOf(previousRows.size, currentRows.size)
        return (0 until max).mapNotNull { i ->
            val next = currentRows.getOrNull(i) ?: ""
            if (previousRows.getOrNull(i) == next) null else RowChange(i, next)
        }
    }
}
