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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CliSessionControllerTest {
    @Test
    fun `pre-start cancelled command clears slot for drain and later commands`() = runTest {
        val dispatcher = PausingDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val api = RecordingHistoryApi()
        val controller = CliSessionController("local", api, CapturingRenderer(), scope = scope)

        val launching = async(Dispatchers.Default) { controller.loadOlderHistory() }
        withContext(Dispatchers.IO) { dispatcher.dispatched.await() }
        controller.activeCommandForTest().cancel()
        dispatcher.release.countDown()
        launching.await()

        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(1_000) { controller.drain() }
        }
        controller.accept(CliTerminalEvent.Submit("/state"))
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(1_000) { controller.drain() }
        }

        assertEquals(1, api.stateCalls)
        scope.cancel()
    }

    @Test
    fun `initialize history requests first page and hydrates messages`() = runTest {
        val api = RecordingHistoryApi(
            pages = ArrayDeque(
                listOf(ConversationHistoryPage(listOf(turn("t1")), "older", true)),
            ),
        )
        val controller = CliSessionController("local", api, CapturingRenderer(), scope = this)

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
        val controller = CliSessionController("local", api, CapturingRenderer(), scope = this)

        controller.initializeHistory()
        controller.loadOlderHistory()
        controller.drain()

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
        val controller = CliSessionController("local", api, CapturingRenderer(), scope = this)

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
            scope = this,
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
        val controller = CliSessionController("local", api, CapturingRenderer(), scope = this)

        controller.initializeHistory()
        controller.loadOlderHistory()
        controller.drain()

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
        val controller = CliSessionController("local", api, CapturingRenderer(), scope = this)
        controller.initializeHistory()
        val olderGate = CompletableDeferred<Unit>()
        api.historyGate = olderGate

        controller.loadOlderHistory()
        controller.loadOlderHistory()
        testScheduler.runCurrent()
        assertEquals(2, api.historyCalls.size)
        val draining = async { controller.drain() }
        testScheduler.runCurrent()
        assertFalse(draining.isCompleted)
        olderGate.complete(Unit)
        draining.await()

        assertEquals(
            listOf(HistoryCall(50, null), HistoryCall(50, "before-t2")),
            api.historyCalls,
        )
    }

    @Test
    fun `history older command launches one tracked request while active`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val api = RecordingHistoryApi(
            pages = ArrayDeque(
                listOf(
                    ConversationHistoryPage(listOf(turn("t2")), "before-t2", true),
                    ConversationHistoryPage(listOf(turn("t1")), null, false),
                ),
            ),
        )
        val controller = CliSessionController("local", api, CapturingRenderer(), scope = this)
        controller.initializeHistory()
        api.historyGate = gate

        controller.accept(CliTerminalEvent.Submit("/history older"))
        controller.accept(CliTerminalEvent.Submit("/history older"))
        testScheduler.runCurrent()

        assertEquals(2, api.historyCalls.size)
        assertTrue(controller.state.historyLoading)
        val draining = async { controller.drain() }
        testScheduler.runCurrent()
        assertFalse(draining.isCompleted)
        gate.complete(Unit)
        draining.await()
        assertTrue(controller.state.historyExhausted)
    }

    @Test
    fun `history older command reports exhaustion without api call`() = runTest {
        val api = RecordingHistoryApi(
            pages = ArrayDeque(
                listOf(ConversationHistoryPage(listOf(turn("t1")), null, false)),
            ),
        )
        val controller = CliSessionController("local", api, CapturingRenderer(), scope = this)
        controller.initializeHistory()

        controller.accept(CliTerminalEvent.Submit("/history older"))
        controller.drain()

        assertEquals(listOf(HistoryCall(50, null)), api.historyCalls)
        assertEquals("No older conversation history.", controller.state.notice)
        assertFalse(controller.state.historyLoading)
    }

    @Test
    fun `help includes compact older history command`() = runTest {
        val controller = CliSessionController("local", RecordingHistoryApi(), CapturingRenderer(), scope = this)

        controller.accept(CliTerminalEvent.Submit("/help"))

        assertTrue(controller.state.notice.orEmpty().contains("/history older"))
        assertTrue(controller.state.notice.orEmpty().lineSequence().count() <= 3)
    }

    @Test
    fun `older history returns before suspended api and drain tracks it`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val api = RecordingHistoryApi(
            pages = ArrayDeque(
                listOf(
                    ConversationHistoryPage(listOf(turn("t2")), "before-t2", true),
                    ConversationHistoryPage(listOf(turn("t1")), null, false),
                ),
            ),
        )
        val controller = CliSessionController("local", api, CapturingRenderer(), scope = this)
        controller.initializeHistory()
        api.historyGate = gate

        controller.loadOlderHistory()
        testScheduler.runCurrent()

        assertEquals(2, api.historyCalls.size)
        assertTrue(controller.state.historyLoading)
        val draining = async { controller.drain() }
        testScheduler.runCurrent()
        assertFalse(draining.isCompleted)
        gate.complete(Unit)
        draining.await()
    }

    @Test
    fun `history command cannot be orphaned by state or diagnostics commands`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val api = RecordingHistoryApi(
            pages = ArrayDeque(
                listOf(
                    ConversationHistoryPage(listOf(turn("t2")), "before-t2", true),
                    ConversationHistoryPage(listOf(turn("t1")), null, false),
                ),
            ),
        )
        val controller = CliSessionController(
            "local",
            api,
            CapturingRenderer(),
            diagnosticsToken = "token",
            scope = this,
        )
        controller.initializeHistory()
        api.historyGate = gate
        controller.loadOlderHistory()
        testScheduler.runCurrent()

        controller.accept(CliTerminalEvent.Submit("/state"))
        controller.accept(CliTerminalEvent.ToggleDiagnostics)
        testScheduler.runCurrent()

        assertEquals(0, api.stateCalls)
        assertEquals(0, api.diagnosticsCalls)
        val draining = async { controller.drain() }
        testScheduler.runCurrent()
        assertFalse(draining.isCompleted)
        gate.complete(Unit)
        draining.await()
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
        val ids = controller.state.messages.map { it.id }
        assertTrue(ids[0].endsWith(":user"))
        assertEquals(ids[0].removeSuffix(":user") + ":assistant", ids[1])
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

    private class PausingDispatcher : CoroutineDispatcher() {
        val dispatched = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
            dispatched.countDown()
            release.await()
            Dispatchers.Default.dispatch(context, block)
        }
    }

    private class RecordingHistoryApi(
        private val pages: ArrayDeque<ConversationHistoryPage> = ArrayDeque(),
        private val failure: Throwable? = null,
        var historyGate: CompletableDeferred<Unit>? = null,
    ) : OpenEdenServerApi {
        val historyCalls = mutableListOf<HistoryCall>()
        var stateCalls = 0
        var diagnosticsCalls = 0

        override suspend fun health() = true
        override suspend fun chat(userId: String, text: String) = ChatResponse("req", "completed", "fallback")
        override fun chatStream(userId: String, text: String, clientRequestId: String) = flowOf<ChatStreamEvent>()
        override suspend fun history(limit: Int, before: String?): ConversationHistoryPage {
            historyCalls += HistoryCall(limit, before)
            historyGate?.await()
            failure?.let { throw it }
            return pages.removeFirst()
        }
        override suspend fun state(userId: String): PublicState {
            stateCalls += 1
            return PublicState("CLI:$userId", "ready", 0.0f, false)
        }
        override suspend fun diagnostics(userId: String, token: String): io.openeden.client.DiagnosticState {
            diagnosticsCalls += 1
            error("unused")
        }
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

    private fun CliSessionController.activeCommandForTest(): Job {
        val field = CliSessionController::class.java.getDeclaredField("activeCommand")
        field.isAccessible = true
        return field.get(this) as Job
    }
}
