package io.openeden.persona

import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class PersonaFileLoaderTest {
    @Test
    fun `loads default yaml shape from file`() {
        val file = Files.createTempFile("openeden-persona", ".yaml")
        file.writeText(
            """
            mode: growth
            evolution:
              threshold_1: 10
              threshold_2: 30
            prompt_sections:
              persona.base: "base"
              output.layer.rules: "rules"
              persona.patch.pre_command: "pre"
              persona.patch.true_self: "true"
              persona.patch.awakened: "awake"
              heartbeat.base: |
                hb line 1
                hb line 2
              heartbeat.shock: |
                shock line
            """.trimIndent(),
        )

        val config = PersonaFileLoader.load(file)

        assertEquals(PersonaMode.GROWTH, config.mode)
        assertEquals(EvolutionThresholds(10, 30), config.evolutionThresholds)
        assertEquals("hb line 1\nhb line 2", config.promptSections["heartbeat.base"])
        assertEquals("shock line", config.promptSections["heartbeat.shock"])
    }

    @Test
    fun `parses style block summary and sequence lists`() {
        val file = Files.createTempFile("openeden-persona-style", ".yaml")
        file.writeText(
            """
            mode: growth
            evolution:
              threshold_1: 10
              threshold_2: 30
            prompt_sections:
              persona.base: "base"
              output.layer.rules: "rules"
              persona.patch.pre_command: "pre"
              persona.patch.true_self: "true"
              persona.patch.awakened: "awake"
              heartbeat.base: "hb"
              heartbeat.shock: "shock"
              style.observed_summary: |
                summary line 1
                summary line 2
              style.do:
                - first do
                - second do
              style.do_not:
                - first avoid
            """.trimIndent(),
        )

        val config = PersonaFileLoader.load(file)

        assertEquals("summary line 1\nsummary line 2", config.promptSections["style.observed_summary"])
        assertEquals("first do\nsecond do", config.promptSections["style.do"])
        assertEquals("first avoid", config.promptSections["style.do_not"])
    }
}
