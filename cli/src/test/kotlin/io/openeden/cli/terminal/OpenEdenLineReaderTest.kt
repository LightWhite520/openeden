package io.openeden.cli.terminal

import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import org.jline.utils.AttributedString
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.function.Supplier
import kotlin.collections.ArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OpenEdenLineReaderTest {
    @Test
    fun `activity owns post when jline has no internal post`() {
        terminal().use { terminal ->
            val reader = InspectableOpenEdenLineReader(terminal)

            reader.replaceInlineActivity(listOf("[status] generating", "ATRI: partial"))

            assertEquals("[status] generating\nATRI: partial", reader.visiblePost())
            reader.replaceInlineActivity(emptyList())
            assertNull(reader.visiblePost())
        }
    }

    @Test
    fun `jline post takes priority and activity returns afterward`() {
        terminal().use { terminal ->
            val reader = InspectableOpenEdenLineReader(terminal)
            reader.replaceInlineActivity(listOf("[status] generating", "ATRI: partial"))

            reader.installJLinePost("completion menu")
            reader.replaceInlineActivity(listOf("[status] generating", "ATRI: updated"))
            assertEquals("completion menu", reader.visiblePost())

            reader.clearJLinePostAndRedisplay()
            assertEquals("[status] generating\nATRI: updated", reader.visiblePost())
        }
    }

    @Test
    fun `full screen frame contains the editable buffer on its input row`() {
        terminal().use { terminal ->
            val reader = InspectableOpenEdenLineReader(terminal)
            reader.setPrompt("> ")
            reader.buffer.write("你好")

            reader.replaceFullScreenFrame(
                rows = listOf("OpenEden", "conversation", "> ", "editor: active=false"),
                inputRow = 2,
            )

            assertEquals("OpenEden\nconversation\n> 你好\neditor: active=false", reader.displayedBuffer())
        }
    }

    @Test
    fun `leaving full screen restores the ordinary prompt and inline activity`() {
        terminal().use { terminal ->
            val reader = InspectableOpenEdenLineReader(terminal)
            reader.setPrompt("> ")
            reader.replaceInlineActivity(listOf("[status] ready"))
            reader.replaceFullScreenFrame(
                rows = listOf("OpenEden", "> ", "editor: active=false"),
                inputRow = 1,
            )

            reader.clearFullScreenFrame()

            assertEquals("> ", reader.visiblePrompt())
            assertEquals("[status] ready", reader.visiblePost())
        }
    }

    private fun terminal() = TerminalBuilder.builder()
        .system(false)
        .streams(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream())
        .dumb(true)
        .build()

    private class InspectableOpenEdenLineReader(terminal: Terminal) : OpenEdenLineReader(terminal) {
        fun visiblePost(): String? = post?.get()?.toString()

        fun visiblePrompt(): String = prompt.toString()

        fun displayedBuffer(): String = getDisplayedBufferWithPrompts(ArrayList()).toString()

        fun installJLinePost(value: String) {
            post = Supplier { AttributedString(value) }
        }

        fun clearJLinePostAndRedisplay() {
            post = null
            redisplay(false)
        }
    }
}
