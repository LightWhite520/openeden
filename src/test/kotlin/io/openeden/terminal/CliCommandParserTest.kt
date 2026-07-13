package io.openeden.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CliCommandParserTest {
    private val parser = CliCommandParser()

    @Test
    fun `parse recognizes commands without arguments`() {
        assertEquals(CliCommand.Help, parser.parse("  /help  "))
        assertEquals(CliCommand.State, parser.parse("/state"))
        assertEquals(CliCommand.Clear, parser.parse("/clear"))
        assertEquals(CliCommand.Exit, parser.parse("/exit"))
    }

    @Test
    fun `parse recognizes mode arguments`() {
        assertEquals(CliCommand.Mode(CliMode.FULL_SCREEN), parser.parse("/mode full"))
        assertEquals(CliCommand.Mode(CliMode.INLINE), parser.parse("/mode\tinline"))
    }

    @Test
    fun `parse recognizes inspect arguments`() {
        assertEquals(CliCommand.Inspect(visible = true), parser.parse("/inspect on"))
        assertEquals(CliCommand.Inspect(visible = false), parser.parse("/inspect off"))
    }

    @Test
    fun `parse preserves unknown command token including case`() {
        assertEquals(CliCommand.Unknown("/unknown"), parser.parse("/unknown value"))
        assertEquals(CliCommand.Unknown("/HELP"), parser.parse("/HELP"))
        assertEquals(CliCommand.Unknown("hello"), parser.parse("hello there"))
        assertEquals(CliCommand.Unknown(""), parser.parse(" \t "))
    }

    @Test
    fun `mode invalid usage has stable message`() {
        listOf("/mode", "/mode compact", "/mode full extra").forEach { input ->
            val error = assertFailsWith<IllegalArgumentException> { parser.parse(input) }

            assertEquals("Usage: /mode inline|full", error.message)
        }
    }

    @Test
    fun `inspect invalid usage has stable message`() {
        listOf("/inspect", "/inspect maybe", "/inspect on extra").forEach { input ->
            val error = assertFailsWith<IllegalArgumentException> { parser.parse(input) }

            assertEquals("Usage: /inspect on|off", error.message)
        }
    }

    @Test
    fun `argumentless commands reject extra tokens consistently`() {
        mapOf(
            "/help topic" to "Usage: /help",
            "/state now" to "Usage: /state",
            "/clear all" to "Usage: /clear",
            "/exit now" to "Usage: /exit",
        ).forEach { (input, message) ->
            val error = assertFailsWith<IllegalArgumentException> { parser.parse(input) }

            assertEquals(message, error.message)
        }
    }

    @Test
    fun `root completion is stable and prefix filtered`() {
        assertEquals(
            listOf("/help", "/state", "/mode", "/inspect", "/clear", "/exit"),
            parser.complete("/").map { it.value },
        )
        assertEquals(listOf("/mode"), parser.complete("/mo").map { it.value })
        assertEquals(listOf("/inspect"), parser.complete("/ins").map { it.value })
    }

    @Test
    fun `mode completion is stable and prefix filtered`() {
        assertEquals(listOf("full", "inline"), parser.complete("/mode ").map { it.value })
        assertEquals(listOf("full"), parser.complete("/mode f").map { it.value })
        assertEquals(listOf("inline"), parser.complete("/mode i").map { it.value })
    }

    @Test
    fun `inspect completion is stable and prefix filtered`() {
        assertEquals(listOf("on", "off"), parser.complete("/inspect ").map { it.value })
        assertEquals(listOf("off"), parser.complete("/inspect of").map { it.value })
    }

    @Test
    fun `completion stops after complete or extra arguments`() {
        assertTrue(parser.complete("/mode full").isEmpty())
        assertTrue(parser.complete("/mode full ").isEmpty())
        assertTrue(parser.complete("/mode f extra").isEmpty())
        assertTrue(parser.complete("/inspect on").isEmpty())
        assertTrue(parser.complete("/help ").isEmpty())
    }

    @Test
    fun `completion ignores ordinary chat and unsupported slash forms`() {
        assertTrue(parser.complete("").isEmpty())
        assertTrue(parser.complete("hello").isEmpty())
        assertTrue(parser.complete(" /mo").isEmpty())
        assertTrue(parser.complete("/unknown ").isEmpty())
    }

    @Test
    fun `completion candidates expose stable product metadata`() {
        val candidate = parser.complete("/mo").single()

        assertIs<CommandCandidate>(candidate)
        assertEquals("/mode", candidate.value)
        assertTrue(candidate.description.isNotBlank())
        assertEquals(null, candidate.shortcut)
    }
}
