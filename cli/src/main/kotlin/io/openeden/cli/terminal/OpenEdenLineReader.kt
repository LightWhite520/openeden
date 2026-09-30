package io.openeden.cli.terminal

import io.openeden.cli.command.CliCommandMenu
import io.openeden.cli.render.CliCommandMenuRenderer
import io.openeden.cli.render.CliTheme

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
    private var fullScreenRows = emptyList<String>()
    private var fullScreenInputRow = 0
    private val commandMenu = CliCommandMenu()
    private val commandMenuRenderer = CliCommandMenuRenderer(CliTheme.forTerminal(terminal))
    private var menuText = AttributedString.EMPTY
    private val menuPost = Supplier { menuText }
    private var finishingLine = false

    fun replaceInlineActivity(lines: List<String>) = lock.withLock {
        activity = AttributedStringBuilder().apply {
            lines.forEachIndexed { index, line ->
                if (index > 0) append('\n')
                append(AttributedString.fromAnsi(line))
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
        val nextPrompt = AttributedString.fromAnsi(rows.take(inputRow + 1).joinToString("\n"))
        fullScreenRows = rows.toList()
        fullScreenInputRow = inputRow
        fullScreenPrompt = nextPrompt
        fullScreenFooter = AttributedString.fromAnsi(rows.drop(inputRow + 1).joinToString("\n"))
        prompt = nextPrompt
        if (ownsPost()) {
            post = fullScreenPost.takeUnless { fullScreenFooter.length == 0 }
        }
        if (enteringFullScreen) display.reset()
        if (reading) redisplay()
    }

    fun clearFullScreenFrame() = lock.withLock {
        if (fullScreenPrompt == null) return@withLock
        fullScreenPrompt = null
        fullScreenRows = emptyList()
        fullScreenFooter = AttributedString.EMPTY
        prompt = ordinaryPrompt
        if (post === fullScreenPost || post === menuPost) {
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
        if (!finishingLine) updateCommandMenuDisplay()
        super.redisplay(flush)
    }

    override fun doCleanup(nl: Boolean) = lock.withLock {
        // JLine must clear transient rows before committing the edited line to scrollback.
        finishingLine = true
        try {
            super.doCleanup(nl)
        } finally {
            finishingLine = false
        }
    }

    fun dismissCommandMenu(): Boolean = lock.withLock {
        commandMenu.update(buffer.toString(), buffer.cursor())
        val dismissed = commandMenu.dismiss()
        updateCommandMenuDisplay()
        if (dismissed && reading) redisplay()
        dismissed
    }

    override fun upLineOrSearch(): Boolean = navigateMenu(-1) || super.upLineOrSearch()
    override fun downLineOrSearch(): Boolean = navigateMenu(1) || super.downLineOrSearch()
    override fun upLineOrHistory(): Boolean = navigateMenu(-1) || super.upLineOrHistory()
    override fun downLineOrHistory(): Boolean = navigateMenu(1) || super.downLineOrHistory()
    override fun expandOrComplete(): Boolean = completeCommand() != null || super.expandOrComplete()
    override fun completeWord(): Boolean = completeCommand() != null || super.completeWord()

    override fun acceptLine(): Boolean = lock.withLock {
        val completed = completeCommand()
        if (completed?.endsWith(' ') == true) return@withLock true
        commandMenu.dismiss()
        updateCommandMenuDisplay()
        super.acceptLine()
    }

    private fun navigateMenu(delta: Int): Boolean = lock.withLock {
        if (!ownsPost()) return@withLock false
        commandMenu.update(buffer.toString(), buffer.cursor())
        commandMenu.move(delta)
    }

    private fun completeCommand(): String? = lock.withLock {
        if (!ownsPost()) return@withLock null
        commandMenu.update(buffer.toString(), buffer.cursor())
        val completed = commandMenu.selection() ?: return@withLock null
        buffer.clear()
        buffer.write(completed)
        commandMenu.update(completed, buffer.cursor())
        if (!completed.endsWith(' ')) commandMenu.dismiss()
        updateCommandMenuDisplay()
        completed
    }

    private fun ownsPost(): Boolean = post == null || post === activityPost || post === fullScreenPost || post === menuPost

    private fun updateCommandMenuDisplay() {
        if (!ownsPost()) return
        commandMenu.update(buffer.toString(), buffer.cursor())
        val columns = (terminal.size.columns.takeIf { it > 0 } ?: 80) - 1
        val height = if (fullScreenPrompt != null) (fullScreenInputRow - 2).coerceAtLeast(0)
            else ((terminal.size.rows.takeIf { it > 0 } ?: 24) / 2).coerceAtLeast(2)
        val rows = commandMenuRenderer.rows(commandMenu.candidates, commandMenu.selectedIndex, columns, minOf(8, height))
        if (fullScreenPrompt != null) {
            // Overlay existing frame rows so opening the menu cannot move the editable input row.
            val promptRows = fullScreenRows.take(fullScreenInputRow + 1).toMutableList()
            rows.forEachIndexed { index, row -> promptRows[fullScreenInputRow - rows.size + index] = row }
            prompt = if (rows.isEmpty()) fullScreenPrompt else AttributedString.fromAnsi(promptRows.joinToString("\n"))
            post = fullScreenPost.takeUnless { fullScreenFooter.isEmpty() }
        } else {
            menuText = AttributedString.fromAnsi(rows.joinToString("\n"))
            post = if (rows.isNotEmpty()) menuPost else activityPost.takeUnless { activity.isEmpty() }
        }
    }
}
