package io.openeden.cli.terminal

import org.jline.reader.impl.LineReaderImpl
import org.jline.terminal.Terminal
import org.jline.utils.AttributedString
import org.jline.utils.AttributedStringBuilder
import java.util.function.Supplier
import kotlin.concurrent.withLock

internal open class OpenEdenLineReader(
    terminal: Terminal,
    appName: String = "openeden",
) : LineReaderImpl(terminal, appName) {
    private var activity = AttributedString.EMPTY
    private val activityPost = Supplier { activity }
    private var ordinaryPrompt = AttributedString.EMPTY
    private var fullScreenPrompt: AttributedString? = null
    private var fullScreenFooter = AttributedString.EMPTY
    private val fullScreenPost = Supplier { fullScreenFooter }

    fun replaceInlineActivity(lines: List<String>) = lock.withLock {
        activity = AttributedStringBuilder().apply {
            lines.forEachIndexed { index, line ->
                if (index > 0) append('\n')
                append(line)
            }
        }.toAttributedString()

        if (fullScreenPrompt == null && (post == null || post === activityPost)) {
            post = activityPost.takeUnless { activity.length == 0 }
        }
        if (reading) redisplay()
    }

    fun replaceFullScreenFrame(rows: List<String>, inputRow: Int) = lock.withLock {
        require(inputRow in rows.indices) { "Input row must belong to the full-screen frame" }
        val enteringFullScreen = fullScreenPrompt == null
        val nextPrompt = AttributedString(rows.take(inputRow + 1).joinToString("\n"))
        fullScreenPrompt = nextPrompt
        fullScreenFooter = AttributedString(rows.drop(inputRow + 1).joinToString("\n"))
        prompt = nextPrompt
        if (post == null || post === activityPost || post === fullScreenPost) {
            post = fullScreenPost.takeUnless { fullScreenFooter.length == 0 }
        }
        if (enteringFullScreen) display.reset()
        if (reading) redisplay()
    }

    fun clearFullScreenFrame() = lock.withLock {
        if (fullScreenPrompt == null) return@withLock
        fullScreenPrompt = null
        fullScreenFooter = AttributedString.EMPTY
        prompt = ordinaryPrompt
        if (post === fullScreenPost) {
            post = activityPost.takeUnless { activity.length == 0 }
        }
        display.reset()
        if (reading) redisplay()
    }

    override fun setPrompt(prompt: String?) = lock.withLock {
        super.setPrompt(prompt)
        ordinaryPrompt = this.prompt
        fullScreenPrompt?.let { this.prompt = it }
        Unit
    }

    override fun redisplay(flush: Boolean) = lock.withLock {
        if (post == null) {
            post = when {
                fullScreenPrompt != null && fullScreenFooter.length > 0 -> fullScreenPost
                activity.length > 0 -> activityPost
                else -> null
            }
        }
        super.redisplay(flush)
    }
}
