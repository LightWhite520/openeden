package io.openeden.terminal

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CliTextStreamsTest {
    @Test
    fun `UTF-8 stdin consumes only one BOM at the beginning`() {
        val input = UTF8_BOM + UTF8_BOM + "hello".toByteArray(StandardCharsets.UTF_8)

        val streams = createStreams(input)

        assertEquals("\uFEFFhello", streams.reader.readText())
    }

    @Test
    fun `UTF-8 stdin consumes a BOM delivered one byte at a time`() {
        val input = OneByteAtATimeInputStream(
            UTF8_BOM + "\u4E2D\u6587".toByteArray(StandardCharsets.UTF_8),
        )
        val streams = CliTextStreams.create(
            input = input,
            output = ByteArrayOutputStream(),
            error = ByteArrayOutputStream(),
            profile = TerminalEncodingProfile.utf8(),
        )

        assertEquals("\u4E2D\u6587", streams.reader.readText())
    }

    @Test
    fun `UTF-8 stdin stops BOM detection after a mismatching short first line`() {
        val streams = CliTextStreams.create(
            input = ShortOpenInputStream("a\n".toByteArray(StandardCharsets.UTF_8)),
            output = ByteArrayOutputStream(),
            error = ByteArrayOutputStream(),
            profile = TerminalEncodingProfile.utf8(),
        )

        assertEquals("a", streams.reader.buffered().readLine())
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
    fun `zero-length read does not trigger BOM detection`() {
        val input = newUtf8BomStrippingInputStream(FailOnReadInputStream())

        assertEquals(0, input.read(ByteArray(1), 0, 0))
    }

    @Test
    fun `invalid read bounds are rejected before BOM detection`() {
        val input = newUtf8BomStrippingInputStream(FailOnReadInputStream())

        assertFailsWith<IndexOutOfBoundsException> {
            input.read(ByteArray(1), 1, 1)
        }
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

    private fun newUtf8BomStrippingInputStream(input: InputStream): InputStream =
        Class.forName("io.openeden.terminal.Utf8BomStrippingInputStream")
            .getDeclaredConstructor(InputStream::class.java)
            .apply { isAccessible = true }
            .newInstance(input) as InputStream

    private companion object {
        val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    }

    private class OneByteAtATimeInputStream(bytes: ByteArray) : InputStream() {
        private val delegate = ByteArrayInputStream(bytes)

        override fun read(): Int = delegate.read()

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            if (length == 0) 0 else delegate.read(buffer, offset, 1)
    }

    private class ShortOpenInputStream(private val bytes: ByteArray) : InputStream() {
        private var position = 0

        override fun read(): Int {
            if (position >= bytes.size) error("BOM detection read beyond the available first line")
            return bytes[position++].toInt() and 0xFF
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (position >= bytes.size) error("BOM detection read beyond the available first line")
            val count = minOf(length, bytes.size - position)
            bytes.copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }
    }

    private class FailOnReadInputStream : InputStream() {
        override fun read(): Int = error("BOM detection must not read the underlying stream")
    }
}
