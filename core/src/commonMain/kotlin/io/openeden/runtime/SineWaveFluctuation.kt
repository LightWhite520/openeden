package io.openeden.runtime

import io.openeden.bio.VectorDelta
import kotlin.math.PI
import kotlin.math.sin

data class SineWaveDimension(
    val amplitude: Float,
    val frequencyHz: Float,
    val phaseRadians: Float,
    val secondaryAmplitudeRatio: Float = 0.25f,
    val secondaryFrequencyMultiplier: Float = 2.31f,
)

data class SineWaveFluctuationProfile(
    val dimensions: List<SineWaveDimension>,
    val maxMagnitude: Float = MAX_PRETICK_DELTA,
) {
    init {
        require(dimensions.size == 8)
        require(maxMagnitude in 0.0f..MAX_PRETICK_DELTA)
    }
}

class SineWaveFluctuationEngine(
    private val profile: SineWaveFluctuationProfile,
) {
    fun deltaAt(elapsedMillis: Long): VectorDelta {
        val seconds = elapsedMillis.coerceAtLeast(0).toDouble() / 1000.0
        val values = profile.dimensions.map { it.valueAt(seconds, profile.maxMagnitude) }
        return VectorDelta(
            l = values[0],
            p = values[1],
            e = values[2],
            s = values[3],
            tau = values[4],
            v = values[5],
            m = values[6],
            f = values[7],
        )
    }

    private fun SineWaveDimension.valueAt(seconds: Double, maxMagnitude: Float): Float {
        val primary = sin(2.0 * PI * frequencyHz * seconds + phaseRadians)
        val secondary = sin(
            2.0 * PI * frequencyHz * secondaryFrequencyMultiplier * seconds +
                phaseRadians * 1.61803398875,
        )
        val value = amplitude * (primary + secondary * secondaryAmplitudeRatio)
        return value.toFloat().coerceIn(-maxMagnitude, maxMagnitude)
    }
}
