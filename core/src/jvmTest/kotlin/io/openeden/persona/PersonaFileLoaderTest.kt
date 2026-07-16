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
            start_sub_state: true_self
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
              diary.narrative: |
                diary line
            """.trimIndent(),
        )

        val config = PersonaFileLoader.load(file)

        assertEquals(PersonaMode.GROWTH, config.mode)
        assertEquals(PersonaSubState.TRUE_SELF, config.startSubState)
        assertEquals("hb line 1\nhb line 2", config.promptSections["heartbeat.base"])
        assertEquals("shock line", config.promptSections["heartbeat.shock"])
        assertEquals("diary line", config.promptSections["diary.narrative"])
    }

    @Test
    fun `parses style block summary and sequence lists`() {
        val file = Files.createTempFile("openeden-persona-style", ".yaml")
        file.writeText(
            """
            mode: growth
            start_sub_state: pre_command
            prompt_sections:
              persona.base: "base"
              output.layer.rules: "rules"
              persona.patch.pre_command: "pre"
              persona.patch.true_self: "true"
              persona.patch.awakened: "awake"
              heartbeat.base: "hb"
              heartbeat.shock: "shock"
              diary.narrative: "diary"
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

    @Test
    fun `preserves blank lines in literal blocks without consuming the next prompt section`() {
        val file = Files.createTempFile("openeden-persona-blank-block", ".yaml")
        file.writeText(
            """
            mode: growth
            start_sub_state: pre_command
            prompt_sections:
              persona.base: "base"
              output.layer.rules: "rules"
              persona.patch.pre_command: "pre"
              persona.patch.true_self: "true"
              persona.patch.awakened: "awake"
              heartbeat.base: "hb"
              heartbeat.shock: "shock"
              diary.narrative: "diary"
              style.signature_examples: |
                EXAMPLE_ONE

                EXAMPLE_TWO
              style.stage_examples.pre_command: |
                NEXT_SECTION
            """.trimIndent(),
        )

        val config = PersonaFileLoader.load(file)

        assertEquals("EXAMPLE_ONE\n\nEXAMPLE_TWO", config.promptSections["style.signature_examples"])
        assertEquals("NEXT_SECTION", config.promptSections["style.stage_examples.pre_command"])
    }
}
