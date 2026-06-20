package io.openeden.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RuntimeConfigTest {
    @Test
    fun `default runtime config is conservative`() {
        val config = RuntimeConfig.Default

        assertEquals(60_000L, config.tick.intervalMs)
        assertEquals(0.75f, config.omega.highThreshold)
        assertEquals(null, config.owner)
    }

    @Test
    fun `rejects invalid tick interval`() {
        assertFailsWith<IllegalArgumentException> {
            RuntimeConfig.Default.copy(tick = TickConfig(intervalMs = 0L))
        }
    }

    @Test
    fun `rejects invalid omega coefficients`() {
        assertFailsWith<IllegalArgumentException> {
            OmegaAccumulationConfig(sWearRate = -0.1f)
        }
    }
}
