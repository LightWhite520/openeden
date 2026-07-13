package io.openeden.terminal

import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TerminalEncodingProfileTest {
    @Test
    fun `empty environment defaults every stream to UTF-8`() {
        val profile = TerminalEncodingProfile.fromEnvironment(emptyMap())

        assertEquals(StandardCharsets.UTF_8, profile.stdin)
        assertEquals(StandardCharsets.UTF_8, profile.stdout)
        assertEquals(StandardCharsets.UTF_8, profile.stderr)
    }

    @Test
    fun `explicit stdin and stdout encodings are parsed`() {
        val profile = TerminalEncodingProfile.fromEnvironment(
            mapOf(
                "OPENEDEN_STDIN_ENCODING" to "GBK",
                "OPENEDEN_STDOUT_ENCODING" to "GBK",
            ),
        )

        assertEquals("GBK", profile.stdin.name())
        assertEquals("GBK", profile.stdout.name())
        assertEquals(StandardCharsets.UTF_8, profile.stderr)
    }

    @Test
    fun `invalid charset names identify the environment variable and configured value`() {
        val error = assertFailsWith<IllegalArgumentException> {
            TerminalEncodingProfile.fromEnvironment(
                mapOf("OPENEDEN_STDOUT_ENCODING" to "not-a-real-charset"),
            )
        }

        assertTrue(error.message.orEmpty().contains("OPENEDEN_STDOUT_ENCODING"))
        assertTrue(error.message.orEmpty().contains("not-a-real-charset"))
    }
}
