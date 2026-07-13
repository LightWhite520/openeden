package io.openeden.terminal

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.io.PushbackInputStream
import java.io.Reader
import java.nio.charset.StandardCharsets

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
            profile: TerminalEncodingProfile,
        ): CliTextStreams {
            val decodedInput = if (profile.stdin == StandardCharsets.UTF_8) {
                Utf8BomStrippingInputStream(input)
            } else {
                input
            }
            return CliTextStreams(
                reader = BufferedReader(InputStreamReader(decodedInput, profile.stdin)),
                out = PrintWriter(OutputStreamWriter(output, profile.stdout), true),
                err = PrintWriter(OutputStreamWriter(error, profile.stderr), true),
            )
        }
    }
}

private class Utf8BomStrippingInputStream(input: InputStream) : InputStream() {
    private val source = PushbackInputStream(input, UTF8_BOM.size)
    private var checked = false

    override fun read(): Int {
        checkBom()
        return source.read()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        checkBom()
        return source.read(buffer, offset, length)
    }

    override fun available(): Int = source.available()

    override fun close() = source.close()

    private fun checkBom() {
        if (checked) return
        checked = true

        val prefix = ByteArray(UTF8_BOM.size)
        val count = source.read(prefix)
        if (count != UTF8_BOM.size || !prefix.contentEquals(UTF8_BOM)) {
            if (count > 0) source.unread(prefix, 0, count)
        }
    }

    private companion object {
        val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    }
}
