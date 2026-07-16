package io.openeden.cli.terminal

import org.jline.utils.ScreenTerminal

internal fun ScreenTerminal.screenAndScrollbackLines(): List<String> {
    val visibleCells = LongArray(columns * rows)
    dump(visibleCells, null)
    val visibleLines = (0 until rows).map { row ->
        visibleCells.copyOfRange(row * columns, (row + 1) * columns).toPhysicalLine()
    }
    return history.map(LongArray::toPhysicalLine) + visibleLines
}

private fun LongArray.toPhysicalLine(): String = buildString {
    this@toPhysicalLine.forEach { cell ->
        val codePoint = ScreenTerminal.cellCodePoint(cell)
        if (codePoint != 0) appendCodePoint(codePoint)
    }
}.trimEnd()
