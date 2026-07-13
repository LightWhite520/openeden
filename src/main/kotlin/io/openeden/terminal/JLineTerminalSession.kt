package io.openeden.terminal

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jline.keymap.KeyMap
import org.jline.reader.Binding
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.reader.Reference
import org.jline.reader.UserInterruptException
import org.jline.reader.Widget
import org.jline.terminal.Attributes
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import org.jline.terminal.spi.TerminalExt
import org.jline.utils.InfoCmp.Capability
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

private val terminalLogger = LoggerFactory.getLogger(JLineTerminalSession::class.java)

class JLineTerminalSession private constructor(
    override val terminal: Terminal,
    override val lineReader: LineReader,
    private val richSupported: Boolean,
    private val readLine: () -> String?,
    private val lifecycleOperations: TerminalLifecycleOperations,
    private val readDispatcher: CoroutineDispatcher,
    private val widgetEvents: Channel<CliTerminalEvent>,
) : TerminalSession {
    private val collectionStarted = AtomicBoolean(false)
    private val lifecycleLock = Any()
    private var closed = false
    private var fullScreen = false

    override fun events(): Flow<CliTerminalEvent> = channelFlow {
        check(collectionStarted.compareAndSet(false, true)) {
            "Terminal events may only be collected once"
        }

        val readerJob = launch(readDispatcher) {
            try {
                while (currentCoroutineContext().isActive) {
                    try {
                        val line = readLine()
                        if (line == null) {
                            send(CliTerminalEvent.EndOfFile)
                            break
                        }
                        send(CliTerminalEvent.Submit(line))
                    } catch (_: UserInterruptException) {
                        send(CliTerminalEvent.Cancel)
                    } catch (_: EndOfFileException) {
                        send(CliTerminalEvent.EndOfFile)
                        break
                    }
                }
            } finally {
                widgetEvents.close()
            }
        }

        try {
            for (event in widgetEvents) send(event)
            readerJob.join()
        } finally {
            readerJob.cancel()
            widgetEvents.close()
            try {
                close()
            } finally {
                readerJob.cancelAndJoin()
            }
        }
    }

    override fun enterFullScreen(): Boolean = synchronized(lifecycleLock) {
        if (closed) return@synchronized false
        if (!richSupported || !lifecycleOperations.hasFullScreenCapabilities()) return false
        if (!fullScreen) {
            lifecycleOperations.enterFullScreen()
            fullScreen = true
        }
        return true
    }

    override fun exitFullScreen() = synchronized(lifecycleLock) { exitFullScreenLocked() }

    override fun redisplay() {
        lineReader.callWidget(LineReader.REDISPLAY)
    }

    override fun close() = synchronized(lifecycleLock) {
        if (closed) return@synchronized
        closed = true
        widgetEvents.close()

        try {
            exitFullScreenLocked()
        } finally {
            try {
                lifecycleOperations.restoreAttributes()
            } finally {
                lifecycleOperations.closeTerminal()
            }
        }
    }

    private fun exitFullScreenLocked() {
        if (!fullScreen) return
        fullScreen = false
        lifecycleOperations.exitFullScreen()
    }

    companion object {
        private const val KEYMAP_NAME = "openeden"
        private const val CANCEL_WIDGET = "openeden-cancel"
        private const val NEWLINE_WIDGET = "openeden-newline"
        private const val TOGGLE_MODE_WIDGET = "openeden-toggle-mode"
        private const val TOGGLE_DIAGNOSTICS_WIDGET = "openeden-toggle-diagnostics"

        fun create(
            warningSink: (String) -> Unit = { warning -> terminalLogger.warn(warning) },
            historyPath: Path = defaultHistoryPath(),
        ): JLineTerminalSession {
            val terminal = TerminalBuilder.builder()
                .name("openeden")
                .system(true)
                .encoding(UTF_8)
                .stdinEncoding(UTF_8)
                .stdoutEncoding(UTF_8)
                .stderrEncoding(UTF_8)
                .providers("jni,exec")
                .build()
            val richSupported = try {
                richSupportFor(terminal, warningSink)
            } catch (error: Throwable) {
                try {
                    terminal.close()
                } catch (cleanupError: Throwable) {
                    error.addSuppressed(cleanupError)
                }
                throw error
            }
            return buildSession(terminal, historyPath, enterRawMode = true, richSupported = richSupported)
        }

        fun createForTest(
            input: InputStream,
            output: OutputStream,
            historyPath: Path,
        ): JLineTerminalSession {
            val terminal = TerminalBuilder.builder()
                .name("openeden-test")
                .system(false)
                .streams(input, output)
                .encoding(UTF_8)
                .stdinEncoding(UTF_8)
                .stdoutEncoding(UTF_8)
                .stderrEncoding(UTF_8)
                .dumb(true)
                .build()
            return buildSession(terminal, historyPath, enterRawMode = false, richSupported = false)
        }

        fun createForTest(
            terminal: Terminal,
            historyPath: Path,
            enterRawMode: Boolean = false,
            richSupported: Boolean = false,
        ): JLineTerminalSession = buildSession(
            terminal = terminal,
            historyPath = historyPath,
            enterRawMode = enterRawMode,
            richSupported = richSupported,
        )

        internal fun fromTerminal(
            terminal: Terminal,
            historyPath: Path,
            enterRawMode: Boolean,
            richSupported: Boolean,
            readLine: (() -> String?)? = null,
            lifecycleOperations: TerminalLifecycleOperations? = null,
        ): JLineTerminalSession = buildSession(
            terminal = terminal,
            historyPath = historyPath,
            enterRawMode = enterRawMode,
            richSupported = richSupported,
            readLine = readLine,
            lifecycleOperations = lifecycleOperations,
        )

        private fun buildSession(
            terminal: Terminal,
            historyPath: Path,
            enterRawMode: Boolean,
            richSupported: Boolean,
            readLine: (() -> String?)? = null,
            lifecycleOperations: TerminalLifecycleOperations? = null,
        ): JLineTerminalSession {
            var savedAttributes: Attributes? = null

            try {
                Files.createDirectories(historyPath.toAbsolutePath().parent)
                savedAttributes = if (enterRawMode) terminal.enterRawMode() else null
                val widgetEvents = Channel<CliTerminalEvent>(Channel.UNLIMITED)
                val lineReader = LineReaderBuilder.builder()
                    .appName("openeden")
                    .terminal(terminal)
                    .completer(CliCommandCompleter(CliCommandParser()))
                    .variable(LineReader.HISTORY_FILE, historyPath)
                    .option(LineReader.Option.BRACKETED_PASTE, true)
                    .option(LineReader.Option.HISTORY_INCREMENTAL, true)
                    .build()
                lineReader.history.attach(lineReader)
                installWidgets(lineReader, widgetEvents)
                installDedicatedKeyMap(lineReader)

                return JLineTerminalSession(
                    terminal = terminal,
                    lineReader = lineReader,
                    richSupported = richSupported,
                    readLine = readLine ?: lineReader::readLine,
                    lifecycleOperations = lifecycleOperations
                        ?: RealTerminalLifecycleOperations(terminal, savedAttributes),
                    readDispatcher = Dispatchers.IO.limitedParallelism(1),
                    widgetEvents = widgetEvents,
                )
            } catch (error: Throwable) {
                try {
                    savedAttributes?.let(terminal::setAttributes)
                } catch (cleanupError: Throwable) {
                    error.addSuppressed(cleanupError)
                } finally {
                    try {
                        terminal.close()
                    } catch (cleanupError: Throwable) {
                        error.addSuppressed(cleanupError)
                    }
                }
                throw error
            }
        }

        private fun installWidgets(
            lineReader: LineReader,
            events: Channel<CliTerminalEvent>,
        ) {
            lineReader.widgets[CANCEL_WIDGET] = eventWidget(events, CliTerminalEvent.Cancel)
            lineReader.widgets[TOGGLE_MODE_WIDGET] = eventWidget(events, CliTerminalEvent.ToggleMode)
            lineReader.widgets[TOGGLE_DIAGNOSTICS_WIDGET] =
                eventWidget(events, CliTerminalEvent.ToggleDiagnostics)
            lineReader.widgets[NEWLINE_WIDGET] = Widget {
                lineReader.buffer.write("\n")
                true
            }
        }

        private fun eventWidget(
            events: Channel<CliTerminalEvent>,
            event: CliTerminalEvent,
        ): Widget = Widget { events.trySend(event).isSuccess }

        private fun installDedicatedKeyMap(lineReader: LineReader) {
            val source = lineReader.keyMaps[LineReader.EMACS] ?: lineReader.keys
            val dedicated = KeyMap<Binding>().apply {
                unicode = source.unicode
                nomatch = source.nomatch
                ambiguousTimeout = source.ambiguousTimeout
                source.boundKeys.forEach { (sequence, binding) -> bind(binding, sequence) }
                bind(Reference(NEWLINE_WIDGET), KeyMap.alt("\r"), KeyMap.alt("\n"))
                bind(Reference(CANCEL_WIDGET), KeyMap.esc(), KeyMap.ctrl('C'))
                bind(Reference(TOGGLE_MODE_WIDGET), KeyMap.ctrl('T'))
                bind(Reference(TOGGLE_DIAGNOSTICS_WIDGET), KeyMap.ctrl('I'))
            }
            lineReader.keyMaps[KEYMAP_NAME] = dedicated
            check(lineReader.setKeyMap(KEYMAP_NAME)) { "Unable to activate OpenEden keymap" }
        }

        private fun richSupportFor(
            terminal: Terminal,
            warningSink: (String) -> Unit,
        ): Boolean {
            val provider = (terminal as? TerminalExt)?.provider?.name()
            val osName = System.getProperty("os.name", "")
            val supported = TerminalRichModePolicy.isSupported(osName, terminal.type, provider)
            val isWindows = osName.startsWith("Windows", ignoreCase = true)
            val isDumb = terminal.type == Terminal.TYPE_DUMB || terminal.type == Terminal.TYPE_DUMB_COLOR
            if (isWindows && !isDumb && !supported) {
                warningSink("JLine JNI terminal unavailable; using plain line mode.")
            }
            return supported
        }

        private fun defaultHistoryPath(): Path =
            Path.of(System.getProperty("user.home"), ".openeden", "history")
    }
}

private class RealTerminalLifecycleOperations(
    private val terminal: Terminal,
    private val savedAttributes: Attributes?,
) : TerminalLifecycleOperations {
    override fun hasFullScreenCapabilities(): Boolean = REQUIRED_CAPABILITIES.all { capability ->
        terminal.getStringCapability(capability) != null
    }

    override fun enterFullScreen() {
        terminal.puts(Capability.enter_ca_mode)
        terminal.puts(Capability.cursor_invisible)
        terminal.flush()
    }

    override fun exitFullScreen() {
        terminal.puts(Capability.cursor_visible)
        terminal.puts(Capability.exit_ca_mode)
        terminal.flush()
    }

    override fun restoreAttributes() {
        savedAttributes?.let(terminal::setAttributes)
    }

    override fun closeTerminal() {
        terminal.close()
    }

    private companion object {
        val REQUIRED_CAPABILITIES = listOf(
            Capability.enter_ca_mode,
            Capability.exit_ca_mode,
            Capability.cursor_invisible,
            Capability.cursor_visible,
        )
    }
}
