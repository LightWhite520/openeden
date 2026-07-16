package io.openeden.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertFailsWith

class CliIoModeSelectorTest {
    @Test
    fun `interactive no-argument launch creates only the terminal owner`() {
        var terminalCreations = 0
        var streamCreations = 0

        val selected = CliIoModeSelector.select(
            arguments = emptyList(),
            stdinInteractive = true,
            stdoutInteractive = true,
            interactiveFactory = {
                terminalCreations += 1
                "terminal"
            },
            plainFactory = {
                streamCreations += 1
                "streams"
            },
        )

        assertEquals("terminal", selected)
        assertEquals(1, terminalCreations)
        assertEquals(0, streamCreations)
    }

    @Test
    fun `one-shot launch creates only redirected streams`() {
        var terminalCreations = 0
        var streamCreations = 0
        var terminalFailure: Throwable? = AssertionError("not called")

        val selected = CliIoModeSelector.select(
            arguments = listOf("state"),
            stdinInteractive = true,
            stdoutInteractive = true,
            interactiveFactory = {
                terminalCreations += 1
                "terminal"
            },
            plainFactory = { failure ->
                streamCreations += 1
                terminalFailure = failure
                "streams"
            },
        )

        assertEquals("streams", selected)
        assertEquals(0, terminalCreations)
        assertEquals(1, streamCreations)
        assertNull(terminalFailure)
    }

    @Test
    fun `redirected stdin or stdout creates only redirected streams`() {
        listOf(false to true, true to false).forEach { (stdinInteractive, stdoutInteractive) ->
            var terminalCreations = 0
            var streamCreations = 0

            val selected = CliIoModeSelector.select(
                arguments = emptyList(),
                stdinInteractive = stdinInteractive,
                stdoutInteractive = stdoutInteractive,
                interactiveFactory = {
                    terminalCreations += 1
                    "terminal"
                },
                plainFactory = {
                    streamCreations += 1
                    "streams"
                },
            )

            assertEquals("streams", selected)
            assertEquals(0, terminalCreations)
            assertEquals(1, streamCreations)
        }
    }

    @Test
    fun `terminal creation failure falls back to one plain owner`() {
        val terminalError = IllegalStateException("terminal unavailable")
        var streamCreations = 0
        var receivedFailure: Throwable? = null

        val selected = CliIoModeSelector.select(
            arguments = emptyList(),
            stdinInteractive = true,
            stdoutInteractive = true,
            interactiveFactory = { throw terminalError },
            plainFactory = { failure ->
                streamCreations += 1
                receivedFailure = failure
                "streams"
            },
        )

        assertEquals("streams", selected)
        assertEquals(1, streamCreations)
        assertSame(terminalError, receivedFailure)
    }

    @Test
    fun `native linkage failure falls back to one plain owner`() {
        val nativeError = UnsatisfiedLinkError("native terminal unavailable")
        var streamCreations = 0

        val selected = CliIoModeSelector.select(
            arguments = emptyList(),
            stdinInteractive = true,
            stdoutInteractive = true,
            interactiveFactory = { throw nativeError },
            plainFactory = { failure ->
                streamCreations += 1
                assertSame(nativeError, failure)
                "streams"
            },
        )

        assertEquals("streams", selected)
        assertEquals(1, streamCreations)
    }

    @Test
    fun `fatal errors propagate without creating plain streams`() {
        val fatalError = object : Error("fatal") {}
        var streamCreations = 0

        val thrown = assertFailsWith<Error> {
            CliIoModeSelector.select(
                arguments = emptyList(),
                stdinInteractive = true,
                stdoutInteractive = true,
                interactiveFactory = { throw fatalError },
                plainFactory = {
                    streamCreations += 1
                    "streams"
                },
            )
        }

        assertSame(fatalError, thrown)
        assertEquals(0, streamCreations)
    }
}
