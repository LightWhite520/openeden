package io.openeden.runtime

import io.openeden.bio.BioVector
import kotlin.test.Test
import kotlin.test.assertEquals

class OmegaAccumulationTest {
    private val config = OmegaAccumulationConfig(
        highThreshold = 0.75f,
        sWearRate = 0.01f,
        dissonanceWearRate = 0.02f,
        fearEntropyMultiplier = 2.0f,
    )

    @Test
    fun `high entropy increases omega monotonically`() {
        val updated = OmegaAccumulationEngine.accumulate(
            omega = OmegaState(0.2f),
            vector = BioVector.Neutral.copy(s = 0.9f),
            elapsedMillis = 1_000L,
            config = config,
        )

        assertEquals(0.21f, updated.value, 0.0001f)
    }

    @Test
    fun `high dissonance increases omega`() {
        val updated = OmegaAccumulationEngine.accumulate(
            omega = OmegaState(0.2f),
            vector = BioVector(l = 1.0f, p = 0.5f, e = 0.0f, s = 0.5f, tau = 0.0f, v = 0.5f, m = 0.5f, f = 0.5f),
            elapsedMillis = 1_000L,
            config = config,
        )

        assertEquals(0.22f, updated.value, 0.0001f)
    }

    @Test
    fun `high fear with high entropy accelerates omega`() {
        val updated = OmegaAccumulationEngine.accumulate(
            omega = OmegaState(0.2f),
            vector = BioVector.Neutral.copy(s = 0.9f, f = 0.9f),
            elapsedMillis = 1_000L,
            config = config,
        )

        assertEquals(0.22f, updated.value, 0.0001f)
    }

    @Test
    fun `low pressure state does not reduce omega`() {
        val updated = OmegaAccumulationEngine.accumulate(
            omega = OmegaState(0.2f),
            vector = BioVector.Neutral.copy(s = 0.1f, f = 0.1f),
            elapsedMillis = 1_000L,
            config = config,
        )

        assertEquals(0.2f, updated.value)
    }
}
