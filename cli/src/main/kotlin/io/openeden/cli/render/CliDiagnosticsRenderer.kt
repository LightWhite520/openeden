package io.openeden.cli.render

import io.openeden.cli.state.CliDiagnostics
import java.util.Locale

internal object CliDiagnosticsRenderer {
    private val dimensions = listOf("L", "P", "E", "S", "tau", "V", "M", "F")

    fun rows(state: CliDiagnostics): List<String> = listOf(
        "[diagnostics] omega=${state.omega} shock=${state.shockActive} evolution=${state.evolutionIndex} D=${state.derivedDissonance}",
    ) + dimensions.mapIndexed { index, name ->
        val value = state.vector.getOrNull(index)?.let { String.format(Locale.ROOT, "%.3f", it) } ?: "unavailable"
        "$name=$value"
    }.chunked(4).map { "[8D] " + it.joinToString(" ") }
}
