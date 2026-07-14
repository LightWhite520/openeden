package io.openeden.cli.state

data class CliUiState(
    val sessionId: String,
    val mode: CliMode = CliMode.INLINE,
    val messages: List<CliMessage> = emptyList(),
    val requestActive: Boolean = false,
    val stage: String? = null,
    val diagnosticsVisible: Boolean = false,
    val diagnostics: CliDiagnostics? = null,
    val notice: String? = null,
    val columns: Int = 80,
    val rows: Int = 24,
) {
    companion object {
        fun initial(userId: String): CliUiState = CliUiState(sessionId = "CLI:$userId")
    }
}
