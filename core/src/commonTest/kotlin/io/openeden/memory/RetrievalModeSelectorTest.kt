package io.openeden.memory


import io.openeden.bio.InternalBioVector
import io.openeden.runtime.affect.OmegaState
import io.openeden.runtime.affect.ShockState
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
    @Test
    fun `active low intensity shock suppresses mixed retrieval until inactive`() {
        val shock = ShockState(true, 0.59f, "free text", Instant.fromEpochMilliseconds(0), 0.001f)
        val vector = neutralInternal.copy(p = -0.31f, v = -0.21f)
        assertEquals(RetrievalMode.CONGRUENT, RetrievalModeSelector.select(vector, OmegaState(0.2f), shock))
        assertEquals(RetrievalMode.MIXED, RetrievalModeSelector.select(vector, OmegaState(0.2f), shock.copy(active = false)))
        assertEquals(RetrievalMode.CONTRAST, RetrievalModeSelector.select(vector, OmegaState(0.75f), shock))
        assertEquals(RetrievalMode.CONTRAST, RetrievalModeSelector.select(vector, OmegaState(0.2f), shock.copy(intensity = 0.6f)))
    }

    @Test
    fun `mixed retrieval thresholds are exclusive`() {
        for (vector in listOf(neutralInternal.copy(p = -0.3f, v = -0.21f), neutralInternal.copy(p = -0.31f, v = -0.2f))) {
            assertEquals(RetrievalMode.CONGRUENT, RetrievalModeSelector.select(vector, OmegaState(0.2f), null))
        }
    }
}
