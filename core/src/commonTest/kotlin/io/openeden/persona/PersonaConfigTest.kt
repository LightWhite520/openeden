package io.openeden.persona

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PersonaConfigTest {
    @Test
    fun `starting sub state is immutable persona configuration`() {
        val config = PersonaConfig(
            mode = PersonaMode.GROWTH,
            startSubState = PersonaSubState.TRUE_SELF,
            promptSections = emptyMap(),
        )

        assertEquals(PersonaSubState.TRUE_SELF, config.startSubState)
    }

    @Test
    fun `legacy mode rejects non awakened starting point`() {
        assertFailsWith<IllegalArgumentException> {
            PersonaConfig(
                mode = PersonaMode.LEGACY,
                startSubState = PersonaSubState.TRUE_SELF,
                promptSections = emptyMap(),
            )
        }
    }
}
