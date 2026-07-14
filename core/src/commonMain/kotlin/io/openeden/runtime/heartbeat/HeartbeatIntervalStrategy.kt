package io.openeden.runtime.heartbeat

import kotlin.random.Random

fun interface HeartbeatIntervalStrategy {
    fun nextDelayMs(): Long
}

class RandomHeartbeatInterval(
    private val minMs: Long = 5 * 60_000L,
    private val maxMs: Long = 4 * 60 * 60_000L,
    private val random: Random = Random.Default,
) : HeartbeatIntervalStrategy {
    override fun nextDelayMs(): Long = random.nextLong(minMs, maxMs + 1)
}
