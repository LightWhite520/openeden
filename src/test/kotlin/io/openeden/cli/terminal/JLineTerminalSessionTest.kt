package io.openeden.cli.terminal

import io.openeden.cli.command.CliCommandCompleter
import io.openeden.cli.command.CliCommandParser
import io.openeden.cli.render.Size

import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.jline.keymap.KeyMap
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReader
import org.jline.reader.Reference
import org.jline.reader.UserInterruptException
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import org.jline.terminal.impl.AbstractTerminal
import org.jline.utils.InfoCmp.Capability
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.FileAlreadyExistsException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class JLineTerminalSessionTest {
    @Test
    fun `byte stream test session emits a line and eof`() = runTest {
        val input = PipedInputStream(4_096)
        val producer = PipedOutputStream(input)
        val session = JLineTerminalSession.createForTest(
            input = input,
            output = ByteArrayOutputStream(),
            historyPath = Files.createTempDirectory("openeden-jline").resolve("history"),
        )

        val received = Channel<CliTerminalEvent>(Channel.UNLIMITED)
        val collector = launch {
            session.events().collect(received::send)
        }
        yield()
        producer.write("hello\r".toByteArray(StandardCharsets.UTF_8))
        producer.flush()

        assertEquals(CliTerminalEvent.Submit("hello"), received.receive())
        producer.close()
        assertEquals(CliTerminalEvent.EndOfFile, received.receive())
        collector.join()
    }

    @Test
    fun `test session uses utf8 history bracketed paste and a dedicated keymap`() {
        val historyPath = Files.createTempDirectory("openeden-jline").resolve("history")
        val session = JLineTerminalSession.createForTest(
            input = ByteArrayInputStream(ByteArray(0)),
            output = ByteArrayOutputStream(),
            historyPath = historyPath,
        )

        try {
            assertEquals(StandardCharsets.UTF_8, session.terminal.encoding())
            assertEquals(StandardCharsets.UTF_8, session.terminal.inputEncoding())
            assertEquals(StandardCharsets.UTF_8, session.terminal.outputEncoding())
            assertEquals(historyPath, session.lineReader.getVariable(LineReader.HISTORY_FILE))
            assertTrue(session.lineReader.isSet(LineReader.Option.BRACKETED_PASTE))
            assertTrue(session.lineReader.isSet(LineReader.Option.HISTORY_INCREMENTAL))
            assertEquals("openeden", session.lineReader.keyMap)
            assertNotSame(
                session.lineReader.keyMaps[LineReader.EMACS],
                session.lineReader.keyMaps["openeden"],
            )

            val keys = session.lineReader.keys
            assertEquals(Reference("openeden-newline"), keys.getBound(KeyMap.alt("\r")))
            assertEquals(Reference("openeden-newline"), keys.getBound(KeyMap.alt("\n")))
            assertEquals(Reference("openeden-cancel"), keys.getBound(KeyMap.esc()))
            assertEquals(Reference("openeden-cancel"), keys.getBound(KeyMap.ctrl('C')))
            assertEquals(Reference("openeden-toggle-mode"), keys.getBound(KeyMap.ctrl('T')))
            assertEquals(
                session.lineReader.keyMaps.getValue(LineReader.EMACS).getBound("\t"),
                keys.getBound("\t"),
            )
            assertEquals(Reference("openeden-toggle-diagnostics"), keys.getBound(KeyMap.alt('i')))
            assertEquals(
                session.lineReader.keyMaps.getValue(LineReader.EMACS).getBound(KeyMap.ctrl('D')),
                keys.getBound(KeyMap.ctrl('D')),
            )
        } finally {
            session.close()
        }
    }

    @Test
    fun `history is persisted incrementally at the configured utf8 path`() {
        val historyPath = Files.createTempDirectory("openeden-jline").resolve("history")
        val first = JLineTerminalSession.createForTest(
            ByteArrayInputStream(ByteArray(0)),
            ByteArrayOutputStream(),
            historyPath,
        )
        first.lineReader.history.add("persisted command")
        assertEquals(1, first.lineReader.history.size())
        first.close()
        assertTrue(Files.exists(historyPath))
        assertTrue(Files.readString(historyPath, StandardCharsets.UTF_8).contains("persisted command"))

        val second = JLineTerminalSession.createForTest(
            ByteArrayInputStream(ByteArray(0)),
            ByteArrayOutputStream(),
            historyPath,
        )
        try {
            assertEquals("persisted command", second.lineReader.history.iterator().next().line())
        } finally {
            second.close()
        }
    }

    @Test
    fun `construction failure closes the supplied terminal`() {
        val terminal = testTerminal()
        val closeCalls = AtomicInteger()
        (terminal as AbstractTerminal).setOnClose(closeCalls::incrementAndGet)
        val parentFile = Files.createTempFile("openeden-history-parent", ".tmp")

        assertFailsWith<FileAlreadyExistsException> {
            JLineTerminalSession.fromTerminal(
                terminal = terminal,
                historyPath = parentFile.resolve("history"),
                enterRawMode = false,
                richSupported = false,
            )
        }
        assertEquals(1, closeCalls.get())
    }

    @Test
    fun `named widgets dispatch terminal events and newline edits the buffer`() = runTest {
        val terminal = testTerminal()
        val session = JLineTerminalSession.fromTerminal(
            terminal = terminal,
            historyPath = Files.createTempDirectory("openeden-jline").resolve("history"),
            enterRawMode = false,
            richSupported = false,
            readLine = {
                Thread.sleep(100)
                throw EndOfFileException()
            },
        )

        try {
            val events = async { session.events().toList() }
            assertTrue(session.lineReader.widgets.getValue("openeden-cancel").apply())
            assertTrue(session.lineReader.widgets.getValue("openeden-toggle-mode").apply())
            assertTrue(session.lineReader.widgets.getValue("openeden-toggle-diagnostics").apply())
            session.lineReader.buffer.write("before")
            assertTrue(session.lineReader.widgets.getValue("openeden-newline").apply())

            assertEquals(
                listOf(
                    CliTerminalEvent.Cancel,
                    CliTerminalEvent.ToggleMode,
                    CliTerminalEvent.ToggleDiagnostics,
                    CliTerminalEvent.EndOfFile,
                ),
                events.await(),
            )
            assertEquals("before\n", session.lineReader.buffer.toString())
        } finally {
            session.close()
        }
    }

    @Test
    fun `events translate lines interrupts and eof on a dedicated sequential reader`() = runTest {
        val terminal = testTerminal()
        val calls = AtomicInteger()
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val callerThread = Thread.currentThread().name
        val readerThreads = mutableListOf<String>()
        val session = JLineTerminalSession.fromTerminal(
            terminal = terminal,
            historyPath = Files.createTempDirectory("openeden-jline").resolve("history"),
            enterRawMode = false,
            richSupported = false,
            readLine = {
                val concurrent = active.incrementAndGet()
                maxActive.accumulateAndGet(concurrent, ::maxOf)
                readerThreads += Thread.currentThread().name
                try {
                    when (calls.getAndIncrement()) {
                        0 -> "hello"
                        1 -> throw UserInterruptException("partial")
                        2 -> "again"
                        else -> throw EndOfFileException()
                    }
                } finally {
                    active.decrementAndGet()
                }
            },
        )

        assertEquals(
            listOf(
                CliTerminalEvent.Submit("hello"),
                CliTerminalEvent.Cancel,
                CliTerminalEvent.Submit("again"),
                CliTerminalEvent.EndOfFile,
            ),
            session.events().toList(),
        )
        assertEquals(1, maxActive.get())
        assertTrue(readerThreads.all { it != callerThread })
    }

    @Test
    fun `widget event stays ahead of the reader event that follows it`() = runTest {
        val terminal = testTerminal()
        lateinit var session: JLineTerminalSession
        var calls = 0
        session = JLineTerminalSession.fromTerminal(
            terminal = terminal,
            historyPath = Files.createTempDirectory("openeden-jline").resolve("history"),
            enterRawMode = false,
            richSupported = false,
            readLine = {
                when (calls++) {
                    0 -> {
                        session.lineReader.widgets.getValue("openeden-cancel").apply()
                        "hello"
                    }
                    else -> throw EndOfFileException()
                }
            },
        )

        assertEquals(
            listOf(
                CliTerminalEvent.Cancel,
                CliTerminalEvent.Submit("hello"),
                CliTerminalEvent.EndOfFile,
            ),
            session.events().toList(),
        )
    }

    @Test
    fun `null line is eof and a second collector is rejected`() = runTest {
        val terminal = testTerminal()
        val session = JLineTerminalSession.fromTerminal(
            terminal = terminal,
            historyPath = Files.createTempDirectory("openeden-jline").resolve("history"),
            enterRawMode = false,
            richSupported = false,
            readLine = { null },
        )

        assertEquals(listOf(CliTerminalEvent.EndOfFile), session.events().toList())
        val error = assertFailsWith<IllegalStateException> { session.events().toList() }
        assertTrue(error.message.orEmpty().contains("collected once"))
    }

    @Test
    fun `event collection leaves terminal lifecycle open for its owner`() = runTest {
        val terminal = testTerminal()
        val operations = RecordingLifecycleOperations(capabilities = false)
        val session = JLineTerminalSession.fromTerminal(
            terminal = terminal,
            historyPath = Files.createTempDirectory("openeden-jline").resolve("history"),
            enterRawMode = false,
            richSupported = false,
            readLine = { null },
            lifecycleOperations = operations,
        )

        assertEquals(listOf(CliTerminalEvent.EndOfFile), session.events().toList())
        assertTrue(operations.calls.isEmpty())

        session.close()
        assertEquals(listOf("restore", "close"), operations.calls)
    }

    @Test
    fun `external close makes a late reader result a graceful shutdown`() = runTest {
        val terminal = testTerminal()
        val readStarted = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val reads = AtomicInteger()
        val session = JLineTerminalSession.fromTerminal(
            terminal = terminal,
            historyPath = Files.createTempDirectory("openeden-jline").resolve("history"),
            enterRawMode = false,
            richSupported = false,
            readLine = {
                if (reads.getAndIncrement() > 0) throw IllegalStateException("terminal closed")
                readStarted.countDown()
                releaseRead.await()
                "late line"
            },
        )
        val collected = async { runCatching { session.events().toList() } }
        yield()
        withContext(Dispatchers.IO) {
            assertTrue(readStarted.await(2, TimeUnit.SECONDS))
        }

        session.close()
        releaseRead.countDown()

        val result = collected.await()
        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().isEmpty())
    }

    @Test
    fun `unexpected reader failure still propagates`() = runTest {
        val terminal = testTerminal()
        val session = JLineTerminalSession.fromTerminal(
            terminal = terminal,
            historyPath = Files.createTempDirectory("openeden-jline").resolve("history"),
            enterRawMode = false,
            richSupported = false,
            readLine = { throw IllegalStateException("reader failed") },
        )

        val error = assertFailsWith<IllegalStateException> { session.events().toList() }

        assertEquals("reader failed", error.message)
    }

    @Test
    fun `full screen requires rich support and capabilities and is idempotent`() {
        val unsupported = recordingSession(richSupported = false, capabilities = true)
        assertFalse(unsupported.session.enterFullScreen())
        assertTrue(unsupported.operations.calls.isEmpty())
        unsupported.session.close()

        val missingCapabilities = recordingSession(richSupported = true, capabilities = false)
        assertFalse(missingCapabilities.session.enterFullScreen())
        assertTrue(missingCapabilities.operations.calls.isEmpty())
        missingCapabilities.session.close()

        val supported = recordingSession(richSupported = true, capabilities = true)
        assertTrue(supported.session.enterFullScreen())
        assertTrue(supported.session.enterFullScreen())
        supported.session.exitFullScreen()
        supported.session.exitFullScreen()
        assertEquals(
            listOf("enter-alt", "hide-cursor", "flush", "show-cursor", "exit-alt", "flush"),
            supported.operations.calls,
        )
        supported.session.close()
    }

    @Test
    fun `full screen restores the normal cursor capability`() {
        assertEquals(Capability.cursor_normal, TerminalFullScreenCapabilities.cursorRestore)
        assertTrue(TerminalFullScreenCapabilities.required.contains(Capability.cursor_normal))
        assertFalse(TerminalFullScreenCapabilities.required.contains(Capability.cursor_visible))
    }

    @Test
    fun `every partial enter failure immediately performs best effort cleanup`() {
        listOf("enter-alt", "hide-cursor", "flush").forEach { failingOperation ->
            val recording = recordingSession(
                richSupported = true,
                capabilities = true,
                failures = mutableMapOf(failingOperation to 1),
            )

            assertFailsWith<IllegalStateException> { recording.session.enterFullScreen() }

            assertTrue(recording.operations.calls.contains("show-cursor"))
            assertTrue(recording.operations.calls.contains("exit-alt"))
            recording.session.close()
            assertEquals(1, recording.operations.calls.count { it == "restore" })
            assertEquals(1, recording.operations.calls.count { it == "close" })
        }
    }

    @Test
    fun `failed partial cleanup is suppressed and close retries it`() {
        val recording = recordingSession(
            richSupported = true,
            capabilities = true,
            failures = mutableMapOf("hide-cursor" to 1, "show-cursor" to 1),
        )

        val error = assertFailsWith<IllegalStateException> { recording.session.enterFullScreen() }
        assertEquals(1, error.suppressed.size)
        assertEquals(1, recording.operations.calls.count { it == "show-cursor" })

        recording.session.close()

        assertEquals(2, recording.operations.calls.count { it == "show-cursor" })
        assertEquals(2, recording.operations.calls.count { it == "exit-alt" })
        assertEquals(1, recording.operations.calls.count { it == "restore" })
        assertEquals(1, recording.operations.calls.count { it == "close" })
    }

    @Test
    fun `failed exit remains retryable until every exit operation succeeds`() {
        val recording = recordingSession(
            richSupported = true,
            capabilities = true,
            failures = mutableMapOf("exit-alt" to 1),
        )
        assertTrue(recording.session.enterFullScreen())

        assertFailsWith<IllegalStateException> { recording.session.exitFullScreen() }
        recording.session.exitFullScreen()

        assertEquals(2, recording.operations.calls.count { it == "show-cursor" })
        assertEquals(2, recording.operations.calls.count { it == "exit-alt" })
        recording.session.close()
    }

    @Test
    fun `rich mode policy rejects dumb terminals and Windows non-jni providers`() {
        val windowsWarnings = mutableListOf<String>()
        assertFalse(
            TerminalRichModePolicy.isSupported(
                "Windows 11",
                Terminal.TYPE_DUMB,
                "exec",
                { warning -> windowsWarnings += warning },
            ),
        )
        assertTrue(windowsWarnings.isEmpty())

        val interactiveWindowsWarnings = mutableListOf<String>()
        assertFalse(
            TerminalRichModePolicy.isSupported(
                "Windows 11",
                "xterm-256color",
                "exec",
                { warning -> interactiveWindowsWarnings += warning },
            ),
        )
        assertEquals(
            listOf("JLine JNI terminal unavailable; using plain line mode."),
            interactiveWindowsWarnings,
        )

        val nonWindowsWarnings = mutableListOf<String>()
        assertFalse(
            TerminalRichModePolicy.isSupported(
                "Linux",
                Terminal.TYPE_DUMB,
                "exec",
                { warning -> nonWindowsWarnings += warning },
            ),
        )
        assertTrue(nonWindowsWarnings.isEmpty())

        assertTrue(TerminalRichModePolicy.isSupported("Windows 11", "xterm-256color", "jni", {}))
        assertTrue(TerminalRichModePolicy.isSupported("Linux", "xterm-256color", "exec", {}))
    }

    @Test
    fun `close exits alternate screen restores attributes and closes terminal exactly once`() {
        val recording = recordingSession(richSupported = true, capabilities = true)
        assertTrue(recording.session.enterFullScreen())

        recording.session.close()
        recording.session.close()

        assertFalse(recording.session.lineReader.widgets.getValue("openeden-cancel").apply())
        assertFalse(recording.session.enterFullScreen())
        assertEquals(
            listOf(
                "enter-alt",
                "hide-cursor",
                "flush",
                "show-cursor",
                "exit-alt",
                "flush",
                "restore",
                "close",
            ),
            recording.operations.calls,
        )
        assertEquals(1, recording.operations.calls.count { it == "exit-alt" })
        assertEquals(1, recording.operations.calls.count { it == "restore" })
        assertEquals(1, recording.operations.calls.count { it == "close" })
    }

    @Test
    fun `close saves history exactly once before restoring and closing terminal`() {
        val terminal = testTerminal()
        val operations = RecordingLifecycleOperations(capabilities = false)
        val session = JLineTerminalSession.fromTerminal(
            terminal = terminal,
            historyPath = Files.createTempDirectory("openeden-jline").resolve("history"),
            enterRawMode = false,
            richSupported = false,
            readLine = { null },
            lifecycleOperations = operations,
            historySave = { operations.calls += "history-save" },
        )

        session.close()
        session.close()

        assertEquals(listOf("history-save", "restore", "close"), operations.calls)
    }

    @Test
    fun `close suppresses cleanup failures after history save failure`() {
        val terminal = testTerminal()
        val operations = RecordingLifecycleOperations(
            capabilities = false,
            failures = mutableMapOf("restore" to 1, "close" to 1),
        )
        val session = JLineTerminalSession.fromTerminal(
            terminal = terminal,
            historyPath = Files.createTempDirectory("openeden-jline").resolve("history"),
            enterRawMode = false,
            richSupported = false,
            readLine = { null },
            lifecycleOperations = operations,
            historySave = { throw IllegalStateException("failed history-save") },
        )

        val error = assertFailsWith<IllegalStateException> { session.close() }

        assertEquals("failed history-save", error.message)
        assertEquals(listOf("failed restore", "failed close"), error.suppressed.map { it.message })
        assertEquals(listOf("restore", "close"), operations.calls)
        session.close()
    }

    @Test
    fun `completion adapts CliCommandParser candidate values and descriptions`() {
        val completer = CliCommandCompleter(CliCommandParser())
        val candidates = mutableListOf<org.jline.reader.Candidate>()

        completer.complete(line = "/mo", candidates = candidates)

        val candidate = assertIs<org.jline.reader.Candidate>(candidates.single())
        assertEquals("/mode", candidate.value())
        assertEquals("Select the terminal display mode", candidate.descr())
        assertNotEquals("/mo", candidate.value())
    }

    private fun recordingSession(
        richSupported: Boolean,
        capabilities: Boolean,
        failures: MutableMap<String, Int> = mutableMapOf(),
    ): RecordingSession {
        val terminal = testTerminal()
        val operations = RecordingLifecycleOperations(capabilities, failures)
        val session = JLineTerminalSession.fromTerminal(
            terminal = terminal,
            historyPath = Files.createTempDirectory("openeden-jline").resolve("history"),
            enterRawMode = false,
            richSupported = richSupported,
            readLine = { null },
            lifecycleOperations = operations,
        )
        return RecordingSession(session, operations)
    }

    private fun testTerminal(): Terminal = TerminalBuilder.builder()
        .name("openeden-test")
        .system(false)
        .streams(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream())
        .encoding(StandardCharsets.UTF_8)
        .stdinEncoding(StandardCharsets.UTF_8)
        .stdoutEncoding(StandardCharsets.UTF_8)
        .stderrEncoding(StandardCharsets.UTF_8)
        .dumb(true)
        .build()

    private data class RecordingSession(
        val session: JLineTerminalSession,
        val operations: RecordingLifecycleOperations,
    )

    private class RecordingLifecycleOperations(
        private val capabilities: Boolean,
        private val failures: MutableMap<String, Int> = mutableMapOf(),
    ) : TerminalLifecycleOperations {
        val calls = mutableListOf<String>()

        override fun hasFullScreenCapabilities(): Boolean = capabilities

        override fun enterAlternateScreen() = record("enter-alt")

        override fun hideCursor() = record("hide-cursor")

        override fun showCursor() = record("show-cursor")

        override fun exitAlternateScreen() = record("exit-alt")

        override fun flush() = record("flush")

        override fun restoreAttributes() {
            record("restore")
        }

        override fun closeTerminal() {
            record("close")
        }

        private fun record(operation: String) {
            calls += operation
            val remaining = failures[operation] ?: return
            if (remaining <= 1) failures.remove(operation) else failures[operation] = remaining - 1
            throw IllegalStateException("failed $operation")
        }
    }
}
