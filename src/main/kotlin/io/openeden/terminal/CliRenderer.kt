package io.openeden.terminal

data class Size(val columns: Int, val rows: Int)

sealed interface RenderDecision {
    data object Rendered : RenderDecision
    data class FallbackToInline(val notice: String) : RenderDecision
}

interface CliRenderer : AutoCloseable {
    fun render(previous: CliUiState?, current: CliUiState, size: Size): RenderDecision
    override fun close()
}
