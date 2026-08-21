package io.openeden.cli.render

import io.openeden.cli.state.CliEvent
import io.openeden.cli.state.CliMode
import io.openeden.cli.state.CliUiState
import io.openeden.cli.state.reduce
import kotlin.test.Test
import kotlin.test.assertEquals

class SwitchableCliRendererTest {
    @Test
    fun `switches renderer instances without changing presentation state`() {
        val inline = RecordingRenderer()
        val fullInstances = mutableListOf<RecordingRenderer>()
        val renderer = SwitchableCliRenderer(inline) {
            RecordingRenderer().also(fullInstances::add)
        }
        val initial = CliUiState.initial("local")
        val full = initial.reduce(CliEvent.ModeSelected(CliMode.FULL_SCREEN))

        renderer.render(null, initial, Size(100, 30))
        renderer.render(initial, full, Size(100, 30))
        renderer.render(full, initial, Size(100, 30))
        renderer.render(initial, full, Size(100, 30))

        assertEquals(2, inline.renderCalls)
        assertEquals(2, fullInstances.size)
        assertEquals(1, fullInstances.first().closeCalls)
        assertEquals("CLI:local", fullInstances.last().lastState?.sessionId)
    }

    private class RecordingRenderer : CliRenderer {
        var renderCalls = 0
        var closeCalls = 0
        var lastState: CliUiState? = null

        override fun render(previous: CliUiState?, current: CliUiState, size: Size): RenderDecision {
            renderCalls += 1
            lastState = current
            return RenderDecision.Rendered
        }

        override fun close() {
            closeCalls += 1
        }
    }
}
