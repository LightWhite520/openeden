package io.openeden.cli.state

sealed interface CliEvent {
    data class Submitted(val text: String, val id: String) : CliEvent

    data class RequestAccepted(val requestId: String) : CliEvent

    data class StageChanged(val value: String?) : CliEvent

    data class ResponseDelta(val text: String) : CliEvent

    data class RequestCompleted(val response: String) : CliEvent

    data object RequestInterrupted : CliEvent

    data class RequestFailed(val message: String) : CliEvent

    data class ModeSelected(val mode: CliMode) : CliEvent

    data class DiagnosticsVisibilityChanged(val visible: Boolean) : CliEvent

    data class DiagnosticsLoaded(val value: CliDiagnostics) : CliEvent

    data class Resized(val columns: Int, val rows: Int) : CliEvent

    data class Notice(val message: String?) : CliEvent

    data object ClearVisibleHistory : CliEvent
}
