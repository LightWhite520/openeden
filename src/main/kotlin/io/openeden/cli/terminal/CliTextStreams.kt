package io.openeden.cli.terminal

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.io.PushbackInputStream
import java.io.Reader
import java.nio.charset.StandardCharsets
import java.util.Objects

data class CliTextStreams(
    val reader: Reader,
    val out: PrintWriter,
    val err: PrintWriter,
) {
    companion object {
        fun create(
            input: InputStream,
            output: OutputStream,
            error: OutputStream,
        ): CliTextStreams {
            return CliTextStreams(
                reader = BufferedReader(
                    InputStreamReader(Utf8BomStrippingInputStream(input), StandardCharsets.UTF_8),
                ),
                out = utf8Writer(output),
                err = utf8Writer(error),
            )
        }

        private fun utf8Writer(output: OutputStream): PrintWriter = PrintWriter(
            OutputStreamWriter(CloseShieldOutputStream(output), StandardCharsets.UTF_8),
            true,
        )
    }
}

private class CloseShieldOutputStream(private val delegate: OutputStream) : OutputStream() {
    override fun write(value: Int) = delegate.write(value)

    override fun write(buffer: ByteArray, offset: Int, length: Int) = delegate.write(buffer, offset, length)

    override fun flush() = delegate.flush()

    override fun close() = delegate.flush()
}

private class Utf8BomStrippingInputStream(input: InputStream) : InputStream() {
    private val source = PushbackInputStream(input, UTF8_BOM.size)
    private var checked = false

    override fun read(): Int {
        checkBom()
        return source.read()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        Objects.checkFromIndexSize(offset, length, buffer.size)
        if (length == 0) return 0
        checkBom()
        return source.read(buffer, offset, length)
    }

    override fun available(): Int = source.available()

    override fun close() = source.close()

    private fun checkBom() {
        if (checked) return
        checked = true

        val prefix = ByteArray(UTF8_BOM.size)
        var count = 0
        while (count < prefix.size) {
            val byte = source.read()
            if (byte < 0) break
            prefix[count] = byte.toByte()
            if (prefix[count] != UTF8_BOM[count]) {
                source.unread(prefix, 0, count + 1)
                return
            }
            count += 1
        }
        if (count != UTF8_BOM.size) {
            if (count > 0) source.unread(prefix, 0, count)
        }
    }

    private companion object {
        val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    }
}
