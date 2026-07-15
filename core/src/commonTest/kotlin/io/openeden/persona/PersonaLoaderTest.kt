package io.openeden.persona

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PersonaLoaderTest {
    @Test
    fun `loads explicit starting point and required sections from structured data`() {
        val config = MapPersonaLoader.load(
            mapOf(
                "mode" to "growth",
                "start_sub_state" to "true_self",
                "persona.base" to "base",
                "output.layer.rules" to "rules",
                "persona.patch.pre_command" to "pre",
                "persona.patch.true_self" to "true",
                "persona.patch.awakened" to "awake",
                "heartbeat.base" to "hb",
                "heartbeat.shock" to "shock",
                "diary.narrative" to "diary",
            ),
        )

        assertEquals(PersonaMode.GROWTH, config.mode)
        assertEquals(PersonaSubState.TRUE_SELF, config.startSubState)
        assertEquals("hb", config.promptSections["heartbeat.base"])
    }

    @Test
    fun `growth mode requires an explicit starting point`() {
        assertFailsWith<IllegalArgumentException> {
            MapPersonaLoader.load(validPersonaValues(mode = "growth") - "start_sub_state")
        }
    }

    @Test
    fun `legacy mode requires the awakened starting point`() {
        val config = MapPersonaLoader.load(validPersonaValues(mode = "legacy", startSubState = "awakened"))

        assertEquals(PersonaSubState.AWAKENED, config.startSubState)
    }

    @Test
    fun `rejects missing heartbeat data`() {
        assertFailsWith<IllegalArgumentException> {
            MapPersonaLoader.load(
                mapOf(
                    "mode" to "growth",
                    "persona.base" to "base",
                    "output.layer.rules" to "rules",
                    "persona.patch.pre_command" to "pre",
                    "persona.patch.true_self" to "true",
                    "persona.patch.awakened" to "awake",
                    "heartbeat.base" to "hb",
                ),
            )
        }
    }

    private fun validPersonaValues(
        mode: String,
        startSubState: String = "pre_command",
    ): Map<String, String> = mapOf(
        "mode" to mode,
        "start_sub_state" to startSubState,
        "persona.base" to "base",
        "output.layer.rules" to "rules",
        "persona.patch.pre_command" to "pre",
        "persona.patch.true_self" to "true",
        "persona.patch.awakened" to "awake",
        "heartbeat.base" to "hb",
        "heartbeat.shock" to "shock",
        "diary.narrative" to "diary",
    )
}
