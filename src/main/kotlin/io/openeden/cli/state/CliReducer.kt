package io.openeden.cli.state

import io.openeden.client.ConversationTurn

fun CliUiState.reduce(event: CliEvent): CliUiState = when (event) {
    is CliEvent.Submitted -> copy(
        messages = messages + listOf(
            CliMessage(
                id = "${event.id}:user",
                role = CliRole.USER,
                markdown = event.text,
                status = CliMessageStatus.COMPLETE,
                inlineTerminalCommitted = event.inlineTerminalCommitted,
            ),
            CliMessage(
                id = "${event.id}:assistant",
                role = CliRole.ASSISTANT,
                markdown = "",
                status = CliMessageStatus.STREAMING,
            ),
        ),
        requestActive = true,
        stage = PREPARING_STAGE,
        notice = null,
    )
    is CliEvent.RequestAccepted -> copy(stage = PREPARING_STAGE)
    is CliEvent.StageChanged -> copy(stage = event.value)
    is CliEvent.ResponseDelta -> copy(
        messages = messages.updateStreamingAssistant { message ->
            message.copy(markdown = message.markdown + event.text)
        },
    )
    is CliEvent.RequestCompleted -> copy(
        messages = messages.updateStreamingAssistant { message ->
            message.copy(
                markdown = event.response.ifBlank { message.markdown },
                status = CliMessageStatus.COMPLETE,
            )
        },
        requestActive = false,
        stage = null,
    )
    CliEvent.RequestInterrupted -> copy(
        messages = messages.updateStreamingAssistant { message ->
            message.copy(status = CliMessageStatus.INTERRUPTED)
        },
        requestActive = false,
        stage = null,
        notice = "Generation interrupted",
    )
    is CliEvent.RequestFailed -> copy(
        messages = messages.updateStreamingAssistant { message ->
            message.copy(status = CliMessageStatus.FAILED)
        },
        requestActive = false,
        stage = null,
        notice = event.message,
    )
    is CliEvent.ModeSelected -> copy(mode = event.mode)
    is CliEvent.DiagnosticsVisibilityChanged -> copy(
        diagnosticsVisible = event.visible,
        diagnostics = if (event.visible) diagnostics else null,
    )
    is CliEvent.DiagnosticsLoaded -> copy(diagnostics = event.value)
    is CliEvent.Resized -> copy(columns = event.columns, rows = event.rows)
    is CliEvent.Notice -> copy(notice = event.message)
    CliEvent.HistoryLoading -> copy(
        historyLoading = true,
        notice = null,
    )
    is CliEvent.HistoryLoaded -> {
        val historyMessages = event.page.turns.flatMap(ConversationTurn::toCliMessages)
        val existingById = messages.associateBy(CliMessage::id)
        val mergedMessages = buildList {
            val includedIds = mutableSetOf<String>()
            historyMessages.forEach { historyMessage ->
                val message = existingById[historyMessage.id] ?: historyMessage
                if (includedIds.add(message.id)) add(message)
            }
            messages.forEach { message ->
                if (includedIds.add(message.id)) add(message)
            }
        }
        copy(
            messages = mergedMessages,
            historyBefore = event.page.before,
            historyLoading = false,
            historyExhausted = !event.page.hasMore || event.page.before == null,
        )
    }
    is CliEvent.HistoryLoadFailed -> copy(
        historyLoading = false,
        notice = event.message,
    )
    CliEvent.HistoryLoadCancelled -> copy(historyLoading = false)
    CliEvent.ClearVisibleHistory -> copy(
        messages = emptyList(),
        notice = "Visible conversation cleared",
    )
}

private fun ConversationTurn.toCliMessages(): List<CliMessage> = listOf(
    CliMessage(
        id = "$turnId:user",
        role = CliRole.USER,
        markdown = userText,
        status = CliMessageStatus.COMPLETE,
    ),
    CliMessage(
        id = "$turnId:assistant",
        role = CliRole.ASSISTANT,
        markdown = assistantText,
        status = CliMessageStatus.COMPLETE,
    ),
)

private inline fun List<CliMessage>.updateStreamingAssistant(
    transform: (CliMessage) -> CliMessage,
): List<CliMessage> {
    var index = lastIndex
    while (index >= 0) {
        val message = this[index]
        if (message.role == CliRole.ASSISTANT && message.status == CliMessageStatus.STREAMING) {
            return toMutableList().apply { this[index] = transform(message) }
        }
        index -= 1
    }
    return this
}

private const val PREPARING_STAGE = "preparing"
