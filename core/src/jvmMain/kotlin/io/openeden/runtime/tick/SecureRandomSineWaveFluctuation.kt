package io.openeden.runtime.tick

import java.security.SecureRandom
import java.util.Random

object SecureRandomSineWaveFluctuation {
    fun profile(
        random: SecureRandom = SecureRandom(),
        maxAmplitude: Float = 0.02f,
    ): SineWaveFluctuationProfile = profile(random as Random, maxAmplitude)

    internal fun profile(
        random: Random,
        maxAmplitude: Float = 0.02f,
    ): SineWaveFluctuationProfile {
        val dimensions = List(8) {
            SineWaveDimension(
                amplitude = random.nextFloat() * maxAmplitude,
                frequencyHz = 0.00005f + random.nextFloat() * 0.0005f,
                phaseRadians = random.nextFloat() * TWO_PI,
                secondaryAmplitudeRatio = 0.15f + random.nextFloat() * 0.25f,
                secondaryFrequencyMultiplier = 1.7f + random.nextFloat() * 2.6f,
            )
        }
        return SineWaveFluctuationProfile(dimensions)
    }

    private const val TWO_PI: Float = 6.2831855f
}
