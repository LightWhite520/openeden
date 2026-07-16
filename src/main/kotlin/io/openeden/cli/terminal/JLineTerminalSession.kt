package io.openeden.cli.terminal

import io.openeden.cli.command.CliCommandCompleter
import io.openeden.cli.command.CliCommandParser

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    private val openEdenLineReader: OpenEdenLineReader,
    private val richSupported: Boolean,
    private val readLine: () -> String?,
    private val lifecycleOperations: TerminalLifecycleOperations,
    private val readDispatcher: CoroutineDispatcher,
    private val eventQueue: Channel<CliTerminalEvent>,
    private val historySave: () -> Unit,
    private val previousWinchHandler: Terminal.SignalHandler,
) : TerminalSession {
    override val lineReader: LineReader = openEdenLineReader
    private val collectionStarted = AtomicBoolean(false)
    private val lifecycleLock = Any()
    @Volatile
    private var shutdownStarted = false
    private var displayState = DisplayState.INLINE

    override fun events(): Flow<CliTerminalEvent> = channelFlow {
        check(collectionStarted.compareAndSet(false, true)) {
            "Terminal events may only be collected once"
        }

        val readerJob = CoroutineScope(SupervisorJob() + readDispatcher).launch {
            try {
                while (currentCoroutineContext().isActive && !shutdownStarted) {
                    try {
                        val line = readLine()
                        if (line == null) {
                            enqueue(CliTerminalEvent.EndOfFile)
                            break
                        }
                        enqueue(
                            CliTerminalEvent.Submit(
                                text = line,
                                inlineTerminalCommitted = isInlineDisplay(),
                            ),
                        )
                    } catch (_: UserInterruptException) {
                        enqueue(CliTerminalEvent.Cancel)
                    } catch (_: EndOfFileException) {
                        enqueue(CliTerminalEvent.EndOfFile)
                        break
                    } catch (error: Throwable) {
                        if (shutdownStarted) break
                        eventQueue.close(error)
                        break
                    }
                }
            } finally {
                eventQueue.close()
            }
        }

        try {
            for (event in eventQueue) send(event)
            readerJob.join()
        } finally {
            readerJob.cancel()
            eventQueue.close()
            try {
                close()
            } finally { readerJob.cancel() }
        }
    }

    override fun enterFullScreen(): Boolean = synchronized(lifecycleLock) {
        if (displayState == DisplayState.CLOSED || displayState == DisplayState.EXITING) {
            return@synchronized false
        }
        if (!richSupported || !lifecycleOperations.hasFullScreenCapabilities()) return false
        if (displayState == DisplayState.FULLSCREEN) return@synchronized true

        displayState = DisplayState.ENTERING
        try {
            lifecycleOperations.enterAlternateScreen()
            lifecycleOperations.flush()
            displayState = DisplayState.FULLSCREEN
        } catch (error: Throwable) {
            exitDisplayBestEffortLocked()?.let(error::addSuppressed)
            throw error
        }
        true
    }

    override fun exitFullScreen() = synchronized(lifecycleLock) {
        if (displayState == DisplayState.INLINE || displayState == DisplayState.CLOSED) {
            return@synchronized
        }
        exitDisplayBestEffortLocked()?.let { throw it }
        Unit
    }

    override fun redisplay() {
        lineReader.callWidget(LineReader.REDISPLAY)
    }

    override fun replaceInlineActivity(lines: List<String>) {
        openEdenLineReader.replaceInlineActivity(lines)
    }

    override fun replaceFullScreenFrame(rows: List<String>, inputRow: Int) {
        openEdenLineReader.replaceFullScreenFrame(rows, inputRow)
    }

    override fun close() = synchronized(lifecycleLock) {
        if (displayState == DisplayState.CLOSED) return@synchronized
        shutdownStarted = true
        var failure: Throwable? = null
        fun attempt(operation: () -> Unit) {
            try {
                operation()
            } catch (error: Throwable) {
                val previous = failure
                if (previous == null) failure = error else previous.addSuppressed(error)
            }
        }

        attempt { terminal.handle(Terminal.Signal.WINCH, previousWinchHandler) }
        eventQueue.close()
        attempt(historySave)
        if (displayState != DisplayState.INLINE) {
            exitDisplayBestEffortLocked()?.let { exitError ->
                val previous = failure
                if (previous == null) failure = exitError else previous.addSuppressed(exitError)
            }
        }
        attempt(lifecycleOperations::restoreAttributes)
        attempt(lifecycleOperations::closeTerminal)
        displayState = DisplayState.CLOSED
        failure?.let { throw it }
        Unit
    }

    private fun enqueue(event: CliTerminalEvent) {
        val result = eventQueue.trySend(event)
        if (result.isFailure && !shutdownStarted) {
            throw result.exceptionOrNull() ?: IllegalStateException("Terminal event queue rejected an event")
        }
    }

    private fun isInlineDisplay(): Boolean = synchronized(lifecycleLock) {
        displayState == DisplayState.INLINE
    }

    private fun exitDisplayBestEffortLocked(): Throwable? {
        displayState = DisplayState.EXITING
        var failure: Throwable? = null
        fun attempt(operation: () -> Unit) {
            try {
                operation()
            } catch (error: Throwable) {
                val previous = failure
                if (previous == null) failure = error else previous.addSuppressed(error)
            }
        }

        attempt(lifecycleOperations::exitAlternateScreen)
        attempt(lifecycleOperations::flush)
        if (failure == null) {
            openEdenLineReader.clearFullScreenFrame()
            displayState = DisplayState.INLINE
        }
        return failure
    }

    private enum class DisplayState {
        INLINE,
        ENTERING,
        FULLSCREEN,
        EXITING,
        CLOSED,
    }

    companion object {
        private const val KEYMAP_NAME = "openeden"
        private const val CANCEL_WIDGET = "openeden-cancel"
        private const val NEWLINE_WIDGET = "openeden-newline"
        private const val TOGGLE_MODE_WIDGET = "openeden-toggle-mode"
        private const val TOGGLE_DIAGNOSTICS_WIDGET = "openeden-toggle-diagnostics"
        private const val LOAD_OLDER_WIDGET = "openeden-load-older"

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
                .providers(TerminalProviderSelection.forOs(System.getProperty("os.name", "")))
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
            historySave: (() -> Unit)? = null,
        ): JLineTerminalSession = buildSession(
            terminal = terminal,
            historyPath = historyPath,
            enterRawMode = enterRawMode,
            richSupported = richSupported,
            readLine = readLine,
            lifecycleOperations = lifecycleOperations,
            historySave = historySave,
        )

        private fun buildSession(
            terminal: Terminal,
            historyPath: Path,
            enterRawMode: Boolean,
            richSupported: Boolean,
            readLine: (() -> String?)? = null,
            lifecycleOperations: TerminalLifecycleOperations? = null,
            historySave: (() -> Unit)? = null,
        ): JLineTerminalSession {
            var savedAttributes: Attributes? = null
            var previousWinchHandler: Terminal.SignalHandler? = null

            try {
                Files.createDirectories(historyPath.toAbsolutePath().parent)
                savedAttributes = if (enterRawMode) terminal.enterRawMode() else null
                val eventQueue = Channel<CliTerminalEvent>(Channel.UNLIMITED)
                previousWinchHandler = terminal.handle(Terminal.Signal.WINCH) {
                    val size = terminal.size
                    eventQueue.trySend(CliTerminalEvent.Resized(size.columns, size.rows))
                }
                val lineReader = OpenEdenLineReader(terminal).apply {
                    setCompleter(CliCommandCompleter(CliCommandParser()))
                    setVariable(LineReader.HISTORY_FILE, historyPath)
                    option(LineReader.Option.BRACKETED_PASTE, true)
                    option(LineReader.Option.HISTORY_INCREMENTAL, true)
                }
                lineReader.history.attach(lineReader)
                installWidgets(lineReader, eventQueue)
                installDedicatedKeyMap(lineReader)

                return JLineTerminalSession(
                    terminal = terminal,
                    openEdenLineReader = lineReader,
                    richSupported = richSupported,
                    readLine = readLine ?: { lineReader.readLine("> ") },
                    lifecycleOperations = lifecycleOperations
                        ?: RealTerminalLifecycleOperations(terminal, savedAttributes),
                    readDispatcher = Dispatchers.IO.limitedParallelism(1),
                    eventQueue = eventQueue,
                    historySave = historySave ?: lineReader.history::save,
                    previousWinchHandler = previousWinchHandler,
                )
            } catch (error: Throwable) {
                try {
                    previousWinchHandler?.let { terminal.handle(Terminal.Signal.WINCH, it) }
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
            lineReader.widgets[LOAD_OLDER_WIDGET] = eventWidget(events, CliTerminalEvent.LoadOlderHistory)
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
                bind(Reference(TOGGLE_DIAGNOSTICS_WIDGET), KeyMap.alt('i'))
                lineReader.terminal.getStringCapability(Capability.key_ppage)?.let { pageUp ->
                    bind(Reference(LOAD_OLDER_WIDGET), pageUp)
                }
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
            return TerminalRichModePolicy.isSupported(osName, terminal.type, provider, warningSink)
        }

        private fun defaultHistoryPath(): Path =
            Path.of(System.getProperty("user.home"), ".openeden", "history")
    }
}

private class RealTerminalLifecycleOperations(
    private val terminal: Terminal,
    private val savedAttributes: Attributes?,
) : TerminalLifecycleOperations {
    override fun hasFullScreenCapabilities(): Boolean = TerminalFullScreenCapabilities.required.all { capability ->
        terminal.getStringCapability(capability) != null
    }

    override fun enterAlternateScreen() = putRequired(Capability.enter_ca_mode)

    override fun exitAlternateScreen() = putRequired(Capability.exit_ca_mode)

    override fun flush() = terminal.flush()

    override fun restoreAttributes() {
        savedAttributes?.let(terminal::setAttributes)
    }

    override fun closeTerminal() {
        terminal.close()
    }

    private fun putRequired(capability: Capability) {
        check(terminal.puts(capability)) { "Terminal failed to apply capability $capability" }
    }
}
