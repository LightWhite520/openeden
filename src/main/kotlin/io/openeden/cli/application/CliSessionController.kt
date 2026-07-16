package io.openeden.cli.application

import io.openeden.cli.command.CliCommand
import io.openeden.cli.command.CliCommandParser
import io.openeden.cli.render.CliRenderer
import io.openeden.cli.render.RenderDecision
import io.openeden.cli.render.Size
import io.openeden.cli.state.CliDiagnostics
import io.openeden.cli.state.CliEvent
import io.openeden.cli.state.CliMode
import io.openeden.cli.state.CliUiState
import io.openeden.cli.state.reduce
import io.openeden.cli.terminal.CliTerminalEvent
import io.openeden.client.ChatStreamEvent
import io.openeden.client.DiagnosticState
import io.openeden.client.OpenEdenServerApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import java.util.UUID

class CliSessionController(
    private val userId: String,
    private val api: OpenEdenServerApi,
    private val renderer: CliRenderer,
    private val commandParser: CliCommandParser = CliCommandParser(),
    private val diagnosticsToken: String? = System.getenv("OPENEDEN_CLI_DIAGNOSTICS_TOKEN"),
    private val size: () -> Size = { Size(80, 24) },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : AutoCloseable {
    @Volatile
    var state: CliUiState = CliUiState.initial(userId)
        private set
    val isStopped: Boolean get() = stopped

    private var previousState: CliUiState? = null
    private var activeRequest: Job? = null
    private var activeCommand: Job? = null
    private var stopped = false
    private val stateLock = Any()
    private val commandLock = Any()

    fun accept(event: CliTerminalEvent) {
        if (stopped) return
        when (event) {
            is CliTerminalEvent.Submit -> acceptText(
                text = event.text,
                inlineTerminalCommitted = event.inlineTerminalCommitted,
            )
            CliTerminalEvent.Cancel -> cancelActiveRequest()
            CliTerminalEvent.ToggleMode -> dispatch(
                CliEvent.ModeSelected(
                    if (state.mode == CliMode.INLINE) CliMode.FULL_SCREEN else CliMode.INLINE,
                ),
            )
            CliTerminalEvent.ToggleDiagnostics -> setDiagnostics(!state.diagnosticsVisible)
            CliTerminalEvent.LoadOlderHistory -> {
                if (state.mode == CliMode.FULL_SCREEN) loadOlderHistory()
            }
            is CliTerminalEvent.Resized -> dispatch(CliEvent.Resized(event.columns, event.rows))
            CliTerminalEvent.EndOfFile -> stopped = true
        }
    }

    suspend fun drain() {
        activeRequest?.join()
        while (true) {
            val command = synchronized(commandLock) { activeCommand } ?: break
            command.join()
            synchronized(commandLock) {
                if (activeCommand === command && command.isCompleted) activeCommand = null
            }
        }
    }

    suspend fun run(events: Flow<CliTerminalEvent>) {
        events.takeWhile { event ->
            accept(event)
            !stopped
        }.collect { }
        drain()
    }

    suspend fun initializeHistory() {
        launchTrackedCommand { loadHistory(initial = true) }?.await()
    }

    fun loadOlderHistory() {
        val current = state
        if (current.historyLoading || current.historyExhausted) return
        launchTrackedCommand { loadHistory(initial = false) }
    }

    private suspend fun loadHistory(initial: Boolean) {
        val current = state
        if (current.historyLoading || (!initial && current.historyExhausted)) return
        val before = if (initial) null else current.historyBefore
        dispatch(CliEvent.HistoryLoading)
        try {
            val page = api.history(limit = HISTORY_PAGE_SIZE, before = before)
            dispatch(CliEvent.HistoryLoaded(page, initial))
        } catch (error: CancellationException) {
            dispatch(CliEvent.HistoryLoadCancelled)
            throw error
        } catch (_: Throwable) {
            dispatch(CliEvent.HistoryLoadFailed(HISTORY_UNAVAILABLE_MESSAGE))
        }
    }

    private fun acceptText(
        text: String,
        inlineTerminalCommitted: Boolean,
    ) {
        if (text.isBlank()) return
        if (text.startsWith('/')) {
            handleCommand(text)
            return
        }
        if (state.requestActive) {
            dispatch(CliEvent.Notice("A request is already running. Cancel it before sending another."))
            return
        }
        val requestId = "cli_${UUID.randomUUID().toString().replace("-", "")}"
        dispatch(
            CliEvent.Submitted(
                text = text,
                id = requestId,
                inlineTerminalCommitted = inlineTerminalCommitted,
            ),
        )
        activeRequest = scope.launch {
            val pending = StringBuilder()
            var lastDeltaFlushNanos = System.nanoTime()
            fun flushDeltas() {
                if (pending.isEmpty()) return
                dispatch(CliEvent.ResponseDelta(pending.toString()))
                pending.clear()
                lastDeltaFlushNanos = System.nanoTime()
            }
            try {
                api.chatStream(userId, text, requestId).collect { streamEvent ->
                    if (streamEvent is ChatStreamEvent.ResponseDelta) {
                        pending.append(streamEvent.text)
                        if (System.nanoTime() - lastDeltaFlushNanos >= RENDER_INTERVAL_NANOS) flushDeltas()
                    } else {
                        flushDeltas()
                        dispatch(streamEvent.toCliEvent())
                    }
                }
                flushDeltas()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                flushDeltas()
                dispatch(CliEvent.RequestFailed(error.message ?: "stream failed"))
            }
        }
    }

    private fun handleCommand(text: String) {
        val command = runCatching { commandParser.parse(text) }.getOrElse { error ->
            dispatch(CliEvent.Notice(error.message ?: "Invalid command"))
            return
        }
        when (command) {
            CliCommand.Help -> dispatch(CliEvent.Notice(HELP_TEXT))
            CliCommand.State -> launchTrackedCommand {
                runCatching { api.state(userId) }
                    .onSuccess { publicState ->
                        dispatch(
                            CliEvent.Notice(
                                "sessionId=${publicState.sessionId} status=${publicState.status} " +
                                    "omega=${publicState.omega} shockActive=${publicState.shockActive}",
                            ),
                        )
                    }
                    .onFailure { dispatch(CliEvent.Notice(it.message ?: "state unavailable")) }
            }
            CliCommand.HistoryOlder -> {
                if (state.historyExhausted) {
                    dispatch(CliEvent.Notice(NO_OLDER_HISTORY_MESSAGE))
                } else {
                    loadOlderHistory()
                }
            }
            is CliCommand.Mode -> dispatch(CliEvent.ModeSelected(command.mode))
            is CliCommand.Inspect -> setDiagnostics(command.visible)
            CliCommand.Clear -> dispatch(CliEvent.ClearVisibleHistory)
            CliCommand.Exit -> stopped = true
            is CliCommand.Unknown -> dispatch(CliEvent.Notice("Unknown command: ${command.name}"))
        }
    }

    private fun cancelActiveRequest() {
        val request = activeRequest ?: run {
            dispatch(CliEvent.Notice(null))
            return
        }
        activeRequest = null
        request.cancel()
        dispatch(CliEvent.RequestInterrupted)
        scope.launch {
            request.cancelAndJoin()
        }
    }

    private fun setDiagnostics(visible: Boolean) {
        dispatch(CliEvent.DiagnosticsVisibilityChanged(visible))
        if (!visible) return
        val token = diagnosticsToken
        if (token.isNullOrBlank()) {
            dispatch(CliEvent.Notice("Diagnostics unavailable."))
            return
        }
        launchTrackedCommand {
            runCatching { api.diagnostics(userId, token) }
                .onSuccess { dispatch(CliEvent.DiagnosticsLoaded(it.toCliDiagnostics())) }
                .onFailure { dispatch(CliEvent.Notice("Diagnostics unavailable.")) }
        }
    }

    private fun dispatch(event: CliEvent) = synchronized(stateLock) {
        val current = state
        val next = current.reduce(event)
        val previous = previousState
        previousState = current
        state = next
        val renderSize = size()
        val decision = renderer.render(previous, next, renderSize)
        if (decision is RenderDecision.FallbackToInline) {
            val fallback = state
                .reduce(CliEvent.ModeSelected(CliMode.INLINE))
                .reduce(CliEvent.Notice(decision.notice))
            state = fallback
            renderer.render(next, fallback, renderSize)
        }
    }

    private fun launchTrackedCommand(block: suspend () -> Unit): Deferred<Unit>? {
        val command = synchronized(commandLock) {
            if (activeCommand?.isCompleted == true) activeCommand = null
            if (activeCommand != null) return null
            scope.async(start = CoroutineStart.LAZY) {
                try {
                    block()
                } finally {
                    val completedCommand = currentCoroutineContext()[Job]
                    synchronized(commandLock) {
                        if (activeCommand === completedCommand) activeCommand = null
                    }
                }
            }.also { activeCommand = it }
        }
        command.start()
        synchronized(commandLock) {
            if (activeCommand === command && command.isCompleted) activeCommand = null
        }
        return command
    }

    override fun close() {
        stopped = true
        activeRequest?.cancel()
        synchronized(commandLock) { activeCommand }?.cancel()
        scope.cancel()
        renderer.close()
    }

    private fun ChatStreamEvent.toCliEvent(): CliEvent = when (this) {
        is ChatStreamEvent.Accepted -> CliEvent.RequestAccepted(requestId)
        is ChatStreamEvent.Stage -> CliEvent.StageChanged(stage)
        is ChatStreamEvent.ResponseDelta -> CliEvent.ResponseDelta(text)
        is ChatStreamEvent.Completed -> CliEvent.RequestCompleted("")
        is ChatStreamEvent.Error -> CliEvent.RequestFailed(message)
    }

    private fun DiagnosticState.toCliDiagnostics(): CliDiagnostics = CliDiagnostics(
        vector = vector,
        omega = omega,
        shockActive = shockActive,
        shockIntensity = shockIntensity,
        evolutionIndex = evolutionIndex,
        derivedDissonance = derivedDissonance,
    )

    private companion object {
        const val HELP_TEXT = "/state  /history older  /help  /exit\n/mode inline|full  /inspect on|off  /clear"
        const val RENDER_INTERVAL_NANOS = 33_000_000L
        const val HISTORY_PAGE_SIZE = 50
        const val HISTORY_UNAVAILABLE_MESSAGE = "Conversation history unavailable."
        const val NO_OLDER_HISTORY_MESSAGE = "No older conversation history."
    }
}
