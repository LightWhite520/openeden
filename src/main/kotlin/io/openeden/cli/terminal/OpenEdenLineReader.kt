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

    fun replaceInlineActivity(lines: List<String>) = lock.withLock {
        activity = AttributedStringBuilder().apply {
            lines.forEachIndexed { index, line ->
                if (index > 0) append('\n')
                append(line)
            }
        }.toAttributedString()

        if (post == null || post === activityPost) {
            post = activityPost.takeUnless { activity.length == 0 }
        }
        if (reading) redisplay()
    }

    override fun redisplay(flush: Boolean) = lock.withLock {
        if (post == null && activity.length > 0) post = activityPost
        super.redisplay(flush)
    }
}
