package io.openeden.persona

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PersonaLoaderTest {
    @Test
    fun `loads thresholds and required sections from structured data`() {
        val config = MapPersonaLoader.load(
            mapOf(
                "mode" to "growth",
                "evolution.threshold_1" to "10",
                "evolution.threshold_2" to "30",
                "persona.base" to "base",
                "output.layer.rules" to "rules",
                "persona.patch.pre_command" to "pre",
                "persona.patch.true_self" to "true",
                "persona.patch.awakened" to "awake",
                "heartbeat.base" to "hb",
                "heartbeat.shock" to "shock",
            ),
        )

        assertEquals(PersonaMode.GROWTH, config.mode)
        assertEquals(EvolutionThresholds(10, 30), config.evolutionThresholds)
        assertEquals("hb", config.promptSections["heartbeat.base"])
    }

    @Test
    fun `rejects missing heartbeat data`() {
        assertFailsWith<IllegalArgumentException> {
            MapPersonaLoader.load(
                mapOf(
                    "mode" to "growth",
                    "evolution.threshold_1" to "10",
                    "evolution.threshold_2" to "30",
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
}
