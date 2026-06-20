package io.openeden.runtime

import java.security.SecureRandom

/** JVM production interval source backed by SecureRandom (§9.3.3). */
class SecureRandomHeartbeatInterval(
    private val minMs: Long = 5 * 60_000L,
    private val maxMs: Long = 4 * 60 * 60_000L,
    private val random: SecureRandom = SecureRandom(),
) : HeartbeatIntervalStrategy {
    init {
        require(minMs > 0)
        require(maxMs >= minMs)
    }

    override fun nextDelayMs(): Long {
        val bound = maxMs - minMs + 1
        return minMs + random.nextLong(bound)
    }
}
