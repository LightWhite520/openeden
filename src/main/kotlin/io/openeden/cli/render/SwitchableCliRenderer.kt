package io.openeden.cli.render

import io.openeden.cli.state.CliMode
import io.openeden.cli.state.CliUiState

class SwitchableCliRenderer(
    private val inline: CliRenderer,
    private val fullScreenFactory: () -> CliRenderer,
) : CliRenderer {
    private var fullScreen: CliRenderer? = null

    override fun render(previous: CliUiState?, current: CliUiState, size: Size): RenderDecision =
        when (current.mode) {
            CliMode.INLINE -> {
                fullScreen?.close()
                fullScreen = null
                inline.render(previous, current, size)
            }
            CliMode.FULL_SCREEN -> {
                val renderer = fullScreen ?: fullScreenFactory().also { fullScreen = it }
                renderer.render(previous, current, size)
            }
        }

    override fun close() {
        fullScreen?.close()
        fullScreen = null
        inline.close()
    }
}
