package io.openeden.cli.application

import io.openeden.cli.render.CliRenderer
import io.openeden.cli.render.RenderDecision
import io.openeden.cli.render.Size
import io.openeden.cli.state.CliMessageStatus
import io.openeden.cli.state.CliMode
import io.openeden.cli.terminal.CliTerminalEvent
import io.openeden.client.ChatResponse
import io.openeden.client.ChatStreamEvent
import io.openeden.client.ConversationHistoryPage
import io.openeden.client.ConversationTurn
import io.openeden.client.OpenEdenServerApi
import io.openeden.client.PublicState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CliSessionControllerTest {
    @Test
    fun `initialize history requests first page and hydrates messages`() = runTest {
        val api = RecordingHistoryApi(
            pages = ArrayDeque(
                listOf(ConversationHistoryPage(listOf(turn("t1")), "older", true)),
            ),
        )
        val controller = CliSessionController("local", api, CapturingRenderer())

        controller.initializeHistory()

        assertEquals(listOf(HistoryCall(50, null)), api.historyCalls)
        assertEquals(listOf("t1:user", "t1:assistant"), controller.state.messages.map { it.id })
        assertEquals("older", controller.state.historyBefore)
        assertFalse(controller.state.historyExhausted)
    }

    @Test
    fun `older history uses cursor from previous page`() = runTest {
        val api = RecordingHistoryApi(
            pages = ArrayDeque(
                listOf(
                    ConversationHistoryPage(listOf(turn("t2")), "before-t2", true),
                    ConversationHistoryPage(listOf(turn("t1")), null, false),
                ),
            ),
        )
        val controller = CliSessionController("local", api, CapturingRenderer())

        controller.initializeHistory()
        controller.loadOlderHistory()

        assertEquals(listOf(HistoryCall(50, null), HistoryCall(50, "before-t2")), api.historyCalls)
        assertEquals(
            listOf("t1:user", "t1:assistant", "t2:user", "t2:assistant"),
            controller.state.messages.map { it.id },
        )
        assertTrue(controller.state.historyExhausted)
    }

    @Test
    fun `history failure hides internal details`() = runTest {
        val api = RecordingHistoryApi(failure = IllegalStateException("secret backend detail"))
        val controller = CliSessionController("local", api, CapturingRenderer())

        controller.initializeHistory()

        assertEquals("Conversation history unavailable.", controller.state.notice)
        assertFalse(controller.state.historyLoading)
    }

    @Test
    fun `history cancellation propagates without unavailable notice`() = runTest {
        val controller = CliSessionController(
            "local",
            RecordingHistoryApi(failure = CancellationException("cancelled")),
            CapturingRenderer(),
        )

        assertFailsWith<CancellationException> { controller.initializeHistory() }

        assertNull(controller.state.notice)
        assertFalse(controller.state.historyLoading)
    }

    @Test
    fun `exhausted history does not request another page`() = runTest {
        val api = RecordingHistoryApi(
            pages = ArrayDeque(
                listOf(ConversationHistoryPage(listOf(turn("t1")), null, false)),
            ),
        )
        val controller = CliSessionController("local", api, CapturingRenderer())

        controller.initializeHistory()
        controller.loadOlderHistory()

        assertEquals(listOf(HistoryCall(50, null)), api.historyCalls)
    }

    @Test
    fun `concurrent older history requests are single flight`() = runTest {
        val api = RecordingHistoryApi(
            pages = ArrayDeque(
                listOf(
                    ConversationHistoryPage(listOf(turn("t2")), "before-t2", true),
                    ConversationHistoryPage(listOf(turn("t1")), null, false),
                ),
            ),
        )
        val controller = CliSessionController("local", api, CapturingRenderer())
        controller.initializeHistory()
        val olderGate = CompletableDeferred<Unit>()
        api.historyGate = olderGate

        val first = async { controller.loadOlderHistory() }
        testScheduler.runCurrent()
        val second = async { controller.loadOlderHistory() }
        testScheduler.runCurrent()
        assertEquals(2, api.historyCalls.size)
        olderGate.complete(Unit)
        first.await()
        second.await()

        assertEquals(
            listOf(HistoryCall(50, null), HistoryCall(50, "before-t2")),
            api.historyCalls,
        )
    }

    @Test
    fun `burst deltas are coalesced without losing response text`() = runTest {
        val renderer = CapturingRenderer()
        val events = buildList {
            add(ChatStreamEvent.Accepted("r1"))
            repeat(100) { add(ChatStreamEvent.ResponseDelta("x")) }
            add(ChatStreamEvent.Completed("r1", "completed"))
        }
        val controller = CliSessionController(
            userId = "local",
            api = FakeStreamingApi(flowOf(*events.toTypedArray())),
            renderer = renderer,
        )

        controller.accept(CliTerminalEvent.Submit("hello"))
        controller.drain()

        assertEquals("x".repeat(100), controller.state.messages.last().markdown)
        assertTrue(renderer.renderCalls < 10, "render calls=${renderer.renderCalls}")
    }

    @Test
    fun `controller streams one request and does not duplicate on mode toggle`() = runTest {
        val api = FakeStreamingApi(
            flowOf(
                ChatStreamEvent.Accepted("r1"),
                ChatStreamEvent.ResponseDelta("你"),
                ChatStreamEvent.ResponseDelta("好"),
                ChatStreamEvent.Completed("r1", "completed"),
            ),
        )
        val controller = CliSessionController(
            userId = "local",
            api = api,
            renderer = CapturingRenderer(),
        )

        controller.accept(CliTerminalEvent.Submit("hello"))
        controller.accept(CliTerminalEvent.ToggleMode)
        controller.drain()

        assertEquals(1, api.streamCalls)
        assertEquals("你好", controller.state.messages.last().markdown)
        assertEquals(CliMode.FULL_SCREEN, controller.state.mode)
    }

    @Test
    fun `cancel stops active collection and does not retry`() = runTest {
        val api = SuspendedStreamingApi()
        val controller = CliSessionController(
            userId = "local",
            api = api,
            renderer = CapturingRenderer(),
        )

        controller.accept(CliTerminalEvent.Submit("hello"))
        api.started.await()
        controller.accept(CliTerminalEvent.Cancel)
        controller.drain()

        assertEquals(1, api.streamCalls)
        assertEquals(CliMessageStatus.INTERRUPTED, controller.state.messages.last().status)
    }

    private class CapturingRenderer : CliRenderer {
        var renderCalls = 0

        override fun render(previous: io.openeden.cli.state.CliUiState?, current: io.openeden.cli.state.CliUiState, size: Size) =
            RenderDecision.Rendered.also { renderCalls += 1 }

        override fun close() = Unit
    }

    private class FakeStreamingApi(private val events: Flow<ChatStreamEvent>) : OpenEdenServerApi {
        var streamCalls = 0

        override suspend fun health() = true
        override suspend fun chat(userId: String, text: String) = ChatResponse("req", "completed", "fallback")
        override fun chatStream(userId: String, text: String, clientRequestId: String): Flow<ChatStreamEvent> {
            streamCalls += 1
            return events
        }
        override suspend fun history(limit: Int, before: String?) =
            ConversationHistoryPage(emptyList(), null, false)
        override suspend fun state(userId: String) = PublicState("CLI:$userId", "ready", 0.0f, false)
        override suspend fun diagnostics(userId: String, token: String) = error("unused")
        override fun close() = Unit
    }

    private class SuspendedStreamingApi : OpenEdenServerApi {
        val started = CompletableDeferred<Unit>()
        var streamCalls = 0

        override suspend fun health() = true
        override suspend fun chat(userId: String, text: String) = ChatResponse("req", "completed", "fallback")
        override fun chatStream(userId: String, text: String, clientRequestId: String): Flow<ChatStreamEvent> = flow {
            streamCalls += 1
            started.complete(Unit)
            MutableSharedFlow<ChatStreamEvent>().collect { emit(it) }
        }
        override suspend fun history(limit: Int, before: String?) =
            ConversationHistoryPage(emptyList(), null, false)
        override suspend fun state(userId: String) = PublicState("CLI:$userId", "ready", 0.0f, false)
        override suspend fun diagnostics(userId: String, token: String) = error("unused")
        override fun close() = Unit
    }

    private data class HistoryCall(val limit: Int, val before: String?)

    private class RecordingHistoryApi(
        private val pages: ArrayDeque<ConversationHistoryPage> = ArrayDeque(),
        private val failure: Throwable? = null,
        var historyGate: CompletableDeferred<Unit>? = null,
    ) : OpenEdenServerApi {
        val historyCalls = mutableListOf<HistoryCall>()

        override suspend fun health() = true
        override suspend fun chat(userId: String, text: String) = ChatResponse("req", "completed", "fallback")
        override fun chatStream(userId: String, text: String, clientRequestId: String) = flowOf<ChatStreamEvent>()
        override suspend fun history(limit: Int, before: String?): ConversationHistoryPage {
            historyCalls += HistoryCall(limit, before)
            historyGate?.await()
            failure?.let { throw it }
            return pages.removeFirst()
        }
        override suspend fun state(userId: String) = PublicState("CLI:$userId", "ready", 0.0f, false)
        override suspend fun diagnostics(userId: String, token: String) = error("unused")
        override fun close() = Unit
    }

    private fun turn(id: String) = ConversationTurn(
        turnId = id,
        platform = "CLI",
        scopeId = "local",
        userId = "local",
        userText = "user-$id",
        assistantText = "assistant-$id",
        completedAtMs = 1L,
    )
}
