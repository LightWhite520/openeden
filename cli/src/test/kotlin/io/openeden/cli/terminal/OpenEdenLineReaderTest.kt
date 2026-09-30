package io.openeden.cli.terminal

import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import org.jline.utils.AttributedString
import org.jline.utils.AttributedStyle
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.function.Supplier
import kotlin.collections.ArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class OpenEdenLineReaderTest {
    @Test
    fun `slash menu filters navigates and completes arguments without submitting incomplete command`() {
        terminal().use { terminal ->
            val reader = InspectableOpenEdenLineReader(terminal)
            reader.setPrompt("> ")
            reader.buffer.write("/")
            reader.refresh()
            assertTrue(reader.visiblePost().orEmpty().contains("/help"))
            reader.buffer.write("mo")
            reader.refresh()
            assertTrue(reader.visiblePost().orEmpty().contains("/mode"))
            assertFalse(reader.visiblePost().orEmpty().contains("/help"))
            reader.confirm()
            reader.refresh()
            assertEquals("/mode ", reader.buffer.toString())
            assertTrue(reader.visiblePost().orEmpty().contains("full"))
            reader.nextChoice()
            reader.completeChoice()
            reader.refresh()
            assertEquals("/mode inline", reader.buffer.toString())
            assertNull(reader.visiblePost())
        }
    }

    @Test
    fun `menu dismissal restores the latest streaming output and preserves draft`() {
        terminal().use { terminal ->
            val reader = InspectableOpenEdenLineReader(terminal)
            reader.setPrompt("> ")
            reader.replaceInlineActivity(listOf("ATRI: first"))
            reader.buffer.write("/")
            reader.refresh()
            reader.replaceInlineActivity(listOf("ATRI: latest"))
            reader.refresh()
            assertTrue(reader.visiblePost().orEmpty().contains("/help"))
            assertTrue(reader.dismissCommandMenu())
            reader.refresh()
            assertEquals("ATRI: latest", reader.visiblePost())
            assertEquals("/", reader.buffer.toString())
            assertFalse(reader.dismissCommandMenu())
        }
    }

    @Test
    fun `full screen menu overlays conversation while keeping input and footer in place`() {
        terminal().use { terminal ->
            val reader = InspectableOpenEdenLineReader(terminal)
            reader.setPrompt("> ")
            val frame = List(22) { "conversation-$it" } + listOf("> ", "footer")
            reader.replaceFullScreenFrame(frame, 22)
            reader.buffer.write("/")
            reader.refresh()
            val shown = reader.displayedBuffer().lines()
            assertEquals(24, shown.size)
            assertEquals("> /", shown[22])
            assertEquals("footer", shown[23])
            assertTrue(shown.take(22).any { it.contains("/help") })
            reader.replaceFullScreenFrame(frame.map { it.replace("conversation", "updated") }, 22)
            reader.refresh()
            assertTrue(reader.displayedBuffer().contains("/help"))
            reader.dismissCommandMenu()
            reader.refresh()
            assertFalse(reader.displayedBuffer().contains("/help"))
            assertTrue(reader.displayedBuffer().contains("updated-21"))
            assertEquals("> /", reader.displayedBuffer().lines()[22])
        }
    }

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
    fun `inline activity retains ANSI styles without styling the editor or later rows`() {
        terminal().use { terminal ->
            val reader = InspectableOpenEdenLineReader(terminal)
            reader.setPrompt("> ")
            reader.buffer.write("draft")
            reader.buffer.cursor(2)
            val baseline = reader.displayedAttributes()
            val baselineInput = baseline.subSequence(2, baseline.length)

            reader.replaceInlineActivity(listOf("\u001b[1;36mgenerating", "ATRI: partial"))

            val activity = assertNotNull(reader.postAttributes())
            assertEquals("generating\nATRI: partial", activity.toString())
            assertEquals(AttributedStyle.DEFAULT.bold().foreground(AttributedStyle.CYAN).style, activity.styleAt(0).style)
            assertEquals(AttributedStyle.DEFAULT, activity.styleAt(activity.toString().indexOf("ATRI")))
            val displayed = reader.displayedAttributes()
            val inputStart = displayed.toString().indexOf("draft")
            assertEquals(baselineInput, displayed.subSequence(inputStart, inputStart + "draft".length))
            assertEquals("draft", reader.buffer.toString())
            assertEquals(2, reader.buffer.cursor())
        }
    }

    @Test
    fun `styled full screen refresh preserves input cursor and restores the styled inline prompt`() {
        terminal().use { terminal ->
            val reader = InspectableOpenEdenLineReader(terminal)
            reader.setPrompt("\u001b[32m> \u001b[0m")
            reader.buffer.write("\u4f60\u597d")
            reader.buffer.cursor(1)
            val baseline = reader.displayedAttributes()
            val baselineInput = baseline.subSequence(2, baseline.length)

            reader.replaceFullScreenFrame(
                rows = listOf("\u001b[1;36mOpenEden\u001b[0m", "\u001b[32m> ", "\u001b[33m2 messages\u001b[0m"),
                inputRow = 1,
            )

            val displayed = reader.displayedAttributes()
            assertEquals("OpenEden\n> \u4f60\u597d\n2 messages", displayed.toString())
            assertEquals(AttributedStyle.DEFAULT.bold().foreground(AttributedStyle.CYAN).style, displayed.styleAt(0).style)
            val inputStart = displayed.toString().indexOf('\u4f60')
            assertEquals(baselineInput, displayed.subSequence(inputStart, inputStart + baselineInput.length))
            assertEquals(
                AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW).style,
                displayed.styleAt(displayed.toString().indexOf("2 messages")).style,
            )

            reader.replaceFullScreenFrame(
                rows = listOf("\u001b[1;36mOpenEden\u001b[0m", "\u001b[32m> \u001b[0m", "3 messages"),
                inputRow = 1,
            )
            assertEquals("\u4f60\u597d", reader.buffer.toString())
            assertEquals(1, reader.buffer.cursor())
            val refreshed = reader.displayedAttributes()
            val refreshedInputStart = refreshed.toString().indexOf('\u4f60')
            assertEquals(baselineInput, refreshed.subSequence(refreshedInputStart, refreshedInputStart + baselineInput.length))

            reader.clearFullScreenFrame()

            val restored = reader.displayedAttributes()
            assertEquals("> \u4f60\u597d", restored.toString())
            assertEquals(AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN).style, restored.styleAt(0).style)
            assertEquals(baseline, restored)
            assertEquals(1, reader.buffer.cursor())
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
        fun refresh() = redisplay(false)
        fun nextChoice() = downLineOrSearch()
        fun completeChoice() = expandOrComplete()
        fun confirm() = acceptLine()
        fun visiblePost(): String? = post?.get()?.toString()

        fun postAttributes(): AttributedString? = post?.get()

        fun visiblePrompt(): String = prompt.toString()

        fun displayedBuffer(): String = getDisplayedBufferWithPrompts(ArrayList()).toString()

        fun displayedAttributes(): AttributedString = getDisplayedBufferWithPrompts(ArrayList())

        fun installJLinePost(value: String) {
            post = Supplier { AttributedString(value) }
        }

        fun clearJLinePostAndRedisplay() {
            post = null
            redisplay(false)
        }
    }
}
