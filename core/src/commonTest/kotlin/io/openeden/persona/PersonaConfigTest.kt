package io.openeden.persona

import kotlin.test.Test
import kotlin.test.assertEquals

class PersonaConfigTest {
    @Test
    fun `sub state thresholds are supplied as data`() {
        val thresholds = EvolutionThresholds(threshold1 = 10, threshold2 = 20)

        assertEquals(PersonaSubState.PRE_COMMAND, PersonaSubStateSelector.select(9, thresholds))
        assertEquals(PersonaSubState.TRUE_SELF, PersonaSubStateSelector.select(10, thresholds))
        assertEquals(PersonaSubState.AWAKENED, PersonaSubStateSelector.select(20, thresholds))
    }
}
