package io.openeden.cli.render

import io.openeden.cli.state.CliDiagnostics
import io.openeden.cli.state.CliMessage
import io.openeden.cli.state.CliMessageStatus
import io.openeden.cli.state.CliRole
import io.openeden.cli.state.CliUiState

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FullScreenCliRendererTest {
    @Test fun `falls back when terminal is too small`() {
        val renderer = FullScreenCliRenderer(FakeFullscreenSink(capable = true))
        assertEquals(RenderDecision.FallbackToInline("Terminal too small for full-screen mode."), renderer.render(CliUiState.initial("x"), Size(79, 24)))
    }
    @Test fun `capability failure has distinct fallback reason`() {
        val renderer = FullScreenCliRenderer(FakeFullscreenSink(capable = false))
        assertEquals(RenderDecision.FallbackToInline("Terminal does not support full-screen capabilities."), renderer.render(CliUiState.initial("x"), Size(100, 30)))
    }
    @Test fun `close is idempotent`() { val sink = FakeFullscreenSink(true); val renderer = FullScreenCliRenderer(sink); renderer.close(); renderer.close(); assertTrue(sink.closed) }

    @Test fun `rich layout includes session editor and visible diagnostics`() {
        val sink = FakeFullscreenSink(true)
        val renderer = FullScreenCliRenderer(sink)
        val state = CliUiState("CLI:local", requestActive = true, diagnosticsVisible = true,
            diagnostics = CliDiagnostics(emptyList(), .4f, true, .7f, 2, .2f))
        assertEquals(RenderDecision.Rendered, renderer.render(state, Size(100, 30)))
        val output = sink.changes.joinToString("\n") { it.text }
        assertTrue(output.contains("session CLI:local"))
        assertTrue(output.contains("editor: active=true"))
        assertTrue(output.contains("diagnostics"))
    }

    @Test fun `shrinking after entry exits fullscreen before inline fallback`() {
        val sink = FakeFullscreenSink(true)
        val renderer = FullScreenCliRenderer(sink)
        renderer.render(CliUiState.initial("x"), Size(100, 30))
        assertEquals(RenderDecision.FallbackToInline("Terminal too small for full-screen mode."), renderer.render(CliUiState.initial("x"), Size(79, 24)))
        assertTrue(sink.closed)
    }

    @Test
    fun `initial viewport shows the newest conversation rows`() {
        val sink = FakeFullscreenSink(true)
        val renderer = FullScreenCliRenderer(sink)
        val state = CliUiState(
            sessionId = "CLI:local",
            messages = (0 until 25).map { message("m$it", "message-$it") },
        )

        renderer.render(state, Size(80, 24))

        val conversation = sink.rows.filter { it.startsWith("│ ") }
        assertEquals("│ > message-7", conversation.first())
        assertEquals("│ > message-24", conversation.last())
    }

    @Test
    fun `older multiline prepend preserves the exact canonical top message`() {
        val sink = FakeFullscreenSink(true)
        val renderer = FullScreenCliRenderer(sink)
        val messages = (0 until 12).map { message("m$it", "line-$it-a ${"x".repeat(80)}") }
        val initial = CliUiState(sessionId = "CLI:local", messages = messages)
        renderer.render(initial, Size(80, 24))
        renderer.render(initial.copy(historyLoading = true), Size(80, 24))
        val topBefore = sink.rows.first { it.startsWith("│ ") }

        val prepended = listOf(
            message("older-a", "same text ${"a".repeat(120)}"),
            message("older-b", "same text ${"b".repeat(120)}"),
        )
        renderer.render(
            initial.copy(messages = prepended + messages, historyLoading = false),
            Size(80, 24),
        )

        assertTrue(topBefore.startsWith("│ > line-0-a"))
        assertEquals(topBefore, sink.rows.first { it.startsWith("│ ") })
    }

    @Test
    fun `resize clamps a paged viewport and a new message restores bottom stickiness`() {
        val sink = FakeFullscreenSink(true)
        val renderer = FullScreenCliRenderer(sink)
        val messages = (0 until 24).map { message("m$it", "message-$it") }
        val initial = CliUiState(sessionId = "CLI:local", messages = messages)
        renderer.render(initial, Size(80, 24))
        renderer.render(initial.copy(historyLoading = true), Size(80, 24))

        renderer.render(initial.copy(historyLoading = true), Size(80, 30))
        assertEquals("│ > message-0", sink.rows.first { it.startsWith("│ ") })

        renderer.render(
            initial.copy(
                historyLoading = false,
                messages = messages + message("new", "newest"),
            ),
            Size(80, 30),
        )
        assertEquals("│ > newest", sink.rows.filter { it.startsWith("│ ") }.last())
    }

    @Test
    fun `paged anchor keeps the same message across narrow wide and height resize`() {
        val sink = FakeFullscreenSink(true)
        val renderer = FullScreenCliRenderer(
            sink,
            FullScreenMessageRowRenderer { message, width ->
                val chunks = message.markdown.chunked(width.coerceAtLeast(1))
                chunks.mapIndexed { line, text -> "${message.id}:$line:$text" }
            },
        )
        val duplicateText = "duplicate ${"x".repeat(180)}"
        val messages = (0 until 40).map { index ->
            message("m$index", if (index in 10..11) duplicateText else "body-$index ${"x".repeat(180)}")
        }
        val initial = CliUiState(sessionId = "CLI:local", messages = messages)
        renderer.render(initial, Size(120, 24))
        renderer.render(initial.copy(historyLoading = true), Size(120, 24))
        val anchoredId = sink.topConversationMessageId()

        renderer.render(initial.copy(historyLoading = true), Size(80, 24))
        assertEquals(anchoredId, sink.topConversationMessageId())

        renderer.render(initial.copy(historyLoading = true), Size(120, 30))
        assertEquals(anchoredId, sink.topConversationMessageId())
    }

    @Test
    fun `large transcript renders and caches only viewport messages`() {
        val sink = FakeFullscreenSink(true)
        val renderedIds = mutableListOf<String>()
        val renderer = FullScreenCliRenderer(
            sink,
            FullScreenMessageRowRenderer { message, _ ->
                renderedIds += message.id
                listOf("> ${message.markdown}")
            },
        )
        val messages = (0 until 1_000).map { message("m$it", "message-$it") }
        val initial = CliUiState(sessionId = "CLI:local", messages = messages)

        renderer.render(initial, Size(80, 24))
        val initialRenderCount = renderedIds.size
        assertTrue(initialRenderCount <= 20, "initial rendered=$initialRenderCount")

        val streaming = messages.dropLast(1) + messages.last().copy(
            markdown = "changed streaming tail",
            status = CliMessageStatus.STREAMING,
        )
        renderer.render(initial.copy(messages = streaming), Size(80, 24))
        assertEquals(initialRenderCount + 1, renderedIds.size)

        val beforeResize = renderedIds.size
        renderer.render(initial.copy(messages = streaming), Size(120, 24))
        assertTrue(renderedIds.size - beforeResize <= 20, "resize rendered=${renderedIds.size - beforeResize}")
    }

    @Test
    fun `height growth backfills anchored viewport and clamps the saved top by message id`() {
        val sink = FakeFullscreenSink(true)
        val renderer = FullScreenCliRenderer(
            sink,
            FullScreenMessageRowRenderer { message, _ -> listOf("${message.id}:0") },
        )
        val messages = (0 until 100).map { message("m$it", "message-$it") }
        val initial = CliUiState(sessionId = "CLI:local", messages = messages)
        renderer.render(initial, Size(80, 24))
        renderer.render(initial.copy(historyLoading = true), Size(80, 24))
        assertEquals("m64", sink.topConversationMessageId())

        renderer.render(initial.copy(historyLoading = true), Size(80, 80))

        val conversation = sink.rows.filter { it.startsWith("│ ") }
        assertEquals(74, conversation.size)
        assertEquals("m26", sink.topConversationMessageId())
        assertEquals("│ m99:0", conversation.last())

        renderer.render(initial.copy(historyLoading = true), Size(80, 80))
        assertEquals("m26", sink.topConversationMessageId())
    }

    @Test
    fun `height growth backfills lines inside the anchor message before earlier messages`() {
        val sink = FakeFullscreenSink(true)
        val renderer = FullScreenCliRenderer(
            sink,
            FullScreenMessageRowRenderer { message, _ ->
                if (message.id == "big") List(100) { line -> "big:$line" } else listOf("${message.id}:0")
            },
        )
        val initial = CliUiState(
            sessionId = "CLI:local",
            messages = listOf(message("earlier", "earlier"), message("big", "big")),
        )
        renderer.render(initial, Size(80, 24))
        renderer.render(initial.copy(historyLoading = true), Size(80, 24))

        renderer.render(initial.copy(historyLoading = true), Size(80, 80))

        val conversation = sink.rows.filter { it.startsWith("│ ") }
        assertEquals(74, conversation.size)
        assertEquals("│ big:26", conversation.first())
        assertEquals("│ big:99", conversation.last())
    }

    private fun message(id: String, text: String) = CliMessage(
        id = id,
        role = CliRole.USER,
        markdown = text,
        status = CliMessageStatus.COMPLETE,
    )
}

private fun FakeFullscreenSink.topConversationMessageId(): String = rows
    .first { it.startsWith("│ ") }
    .removePrefix("│ ")
    .substringBefore(':')

private class FakeFullscreenSink(val capable: Boolean) : FullscreenSink {
    var closed = false
    val changes = mutableListOf<RowChange>()
    val rows = mutableListOf<String>()
    override fun capabilitiesAvailable() = capable
    override fun enter() = capable
    override fun write(changes: List<RowChange>) {
        this.changes += changes
        changes.forEach { change ->
            while (rows.size <= change.index) rows += ""
            rows[change.index] = change.text
        }
        while (rows.lastOrNull().isNullOrEmpty()) rows.removeLast()
    }
    override fun close() { closed = true }
}
