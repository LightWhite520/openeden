package io.openeden.runtime.lifecycle

data class TerminationReason(
    val code: String,
    val requestedAtMs: Long,
) {
    init {
        require(code.isNotBlank()) { "Termination reason code must not be blank" }
        require(code.all { it.code in 0..0x7f }) { "Termination reason code must be ASCII" }
    }
}
