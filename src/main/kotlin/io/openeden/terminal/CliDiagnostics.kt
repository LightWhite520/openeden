package io.openeden.terminal

data class CliDiagnostics(
    val vector: List<Float>,
    val omega: Float,
    val shockActive: Boolean,
    val shockIntensity: Float?,
    val evolutionIndex: Long,
    val derivedDissonance: Float,
)
