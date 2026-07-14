package io.openeden.runtime.tick

data class TickConfig(
    val intervalMs: Long = 60_000L,
) {
    init {
        require(intervalMs > 0) { "tick interval must be positive" }
    }
}
