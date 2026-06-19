package io.openeden.memory

import io.openeden.bio.InternalBioVector
import io.openeden.runtime.OmegaState
import io.openeden.runtime.ShockState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class RetrievalModeSelectorTest {
    private val neutralInternal = InternalBioVector(
        l = 0.0f,
        p = 0.0f,
        e = 0.0f,
        s = 0.0f,
        tau = 0.0f,
        v = 0.0f,
        m = 0.0f,
        f = 0.0f,
    )

    @Test
    fun `shock contrast has priority over every other mode`() {
        val shock = ShockState(
            active = true,
            intensity = 0.6f,
            description = "free text",
            triggeredAt = Instant.fromEpochMilliseconds(0),
            decayLambda = 0.001f,
        )

        val mode = RetrievalModeSelector.select(
            internalVector = neutralInternal.copy(p = -0.9f, v = -0.9f),
            omegaState = OmegaState(0.1f),
            shockState = shock,
        )

        assertEquals(RetrievalMode.CONTRAST, mode)
    }

    @Test
    fun `omega contrast outranks mixed retrieval`() {
        val mode = RetrievalModeSelector.select(
            internalVector = neutralInternal.copy(p = -0.9f, v = -0.9f),
            omegaState = OmegaState(0.75f),
            shockState = null,
        )

        assertEquals(RetrievalMode.CONTRAST, mode)
    }

    @Test
    fun `mild negative state selects mixed retrieval`() {
        val mode = RetrievalModeSelector.select(
            internalVector = neutralInternal.copy(p = -0.31f, v = -0.21f),
            omegaState = OmegaState(0.2f),
            shockState = null,
        )

        assertEquals(RetrievalMode.MIXED, mode)
        assertEquals("[相关记忆 - 尝试寻找平静]", RetrievalModeSelector.injectionLabel(mode))
    }
}
