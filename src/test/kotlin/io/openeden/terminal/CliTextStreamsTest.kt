package io.openeden.terminal

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class CliTextStreamsTest {
    @Test
    fun `UTF-8 stdin consumes only one BOM at the beginning`() {
        val input = UTF8_BOM + UTF8_BOM + "hello".toByteArray(StandardCharsets.UTF_8)

        val streams = createStreams(input)

        assertEquals("\uFEFFhello", streams.reader.readText())
    }

    @Test
    fun `UTF-8 stdin without BOM preserves its first three bytes`() {
        val input = "abcdef".toByteArray(StandardCharsets.UTF_8)

        val streams = createStreams(input)

        assertEquals("abcdef", streams.reader.readText())
    }

    @Test
    fun `UTF-8 stdin shorter than BOM preserves every byte`() {
        val input = "ab".toByteArray(StandardCharsets.UTF_8)

        val streams = createStreams(input)

        assertEquals("ab", streams.reader.readText())
    }

    @Test
    fun `non UTF-8 stdin does not inspect or consume BOM bytes`() {
        val profile = TerminalEncodingProfile(
            stdin = StandardCharsets.ISO_8859_1,
            stdout = StandardCharsets.UTF_8,
            stderr = StandardCharsets.UTF_8,
        )

        val streams = createStreams(UTF8_BOM, profile)

        assertEquals("\u00EF\u00BB\u00BF", streams.reader.readText())
    }

    @Test
    fun `UTF-8 output contains exact payload bytes without BOM`() {
        val output = ByteArrayOutputStream()
        val streams = CliTextStreams.create(
            input = ByteArrayInputStream(ByteArray(0)),
            output = output,
            error = ByteArrayOutputStream(),
            profile = TerminalEncodingProfile.utf8(),
        )

        streams.out.print("\u4F60\u597D")
        streams.out.flush()

        assertContentEquals("\u4F60\u597D".toByteArray(StandardCharsets.UTF_8), output.toByteArray())
    }

    private fun createStreams(
        input: ByteArray,
        profile: TerminalEncodingProfile = TerminalEncodingProfile.utf8(),
    ): CliTextStreams = CliTextStreams.create(
        input = ByteArrayInputStream(input),
        output = ByteArrayOutputStream(),
        error = ByteArrayOutputStream(),
        profile = profile,
    )

    private companion object {
        val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    }
}
