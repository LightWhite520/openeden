package io.openeden.terminal

import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.jline.keymap.KeyMap
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReader
import org.jline.reader.Reference
import org.jline.reader.UserInterruptException
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import org.jline.terminal.impl.AbstractTerminal
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.FileAlreadyExistsException
import java.util.concurrent.atomic.AtomicInteger
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
            assertEquals(Reference("openeden-toggle-diagnostics"), keys.getBound(KeyMap.ctrl('I')))
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
        assertEquals(listOf("enter", "exit"), supported.operations.calls)
        supported.session.close()
    }

    @Test
    fun `rich mode policy rejects dumb terminals and Windows non-jni providers`() {
        assertFalse(TerminalRichModePolicy.isSupported("Windows 11", "xterm-256color", "exec"))
        assertFalse(TerminalRichModePolicy.isSupported("Linux", Terminal.TYPE_DUMB, "jni"))
        assertTrue(TerminalRichModePolicy.isSupported("Windows 11", "xterm-256color", "jni"))
        assertTrue(TerminalRichModePolicy.isSupported("Linux", "xterm-256color", "exec"))
    }

    @Test
    fun `close exits alternate screen restores attributes and closes terminal exactly once`() {
        val recording = recordingSession(richSupported = true, capabilities = true)
        assertTrue(recording.session.enterFullScreen())

        recording.session.close()
        recording.session.close()

        assertFalse(recording.session.lineReader.widgets.getValue("openeden-cancel").apply())
        assertFalse(recording.session.enterFullScreen())
        assertEquals(listOf("enter", "exit", "restore", "close"), recording.operations.calls)
        assertEquals(1, recording.operations.calls.count { it == "exit" })
        assertEquals(1, recording.operations.calls.count { it == "restore" })
        assertEquals(1, recording.operations.calls.count { it == "close" })
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
    ): RecordingSession {
        val terminal = testTerminal()
        val operations = RecordingLifecycleOperations(capabilities)
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
    ) : TerminalLifecycleOperations {
        val calls = mutableListOf<String>()

        override fun hasFullScreenCapabilities(): Boolean = capabilities

        override fun enterFullScreen() {
            calls += "enter"
        }

        override fun exitFullScreen() {
            calls += "exit"
        }

        override fun restoreAttributes() {
            calls += "restore"
        }

        override fun closeTerminal() {
            calls += "close"
        }
    }
}
