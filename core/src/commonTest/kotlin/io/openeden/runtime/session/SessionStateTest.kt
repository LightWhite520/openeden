package io.openeden.runtime.session

import io.openeden.persona.PersonaMode
import io.openeden.persona.PersonaSubState
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

class SessionStateTest {
    @Test
    fun `legacy session rejects a non-awakened starting point`() {
        assertFailsWith<IllegalArgumentException> {
            SessionStateStore.neutral(
                sessionId = "CLI:test",
                personaStartSubState = PersonaSubState.PRE_COMMAND,
                personaMode = PersonaMode.LEGACY,
            )
        }
    }

    @Test
    fun `mutable store rejects changing an existing persona selection`() = runTest {
        val store = MutableSessionStateStore()
        val initial = SessionStateStore.neutral(
            sessionId = "CLI:test",
            personaStartSubState = PersonaSubState.TRUE_SELF,
        )
        store.write(initial)

        assertFailsWith<IllegalArgumentException> {
            store.write(initial.copy(personaStartSubState = PersonaSubState.AWAKENED))
        }
    }
}
