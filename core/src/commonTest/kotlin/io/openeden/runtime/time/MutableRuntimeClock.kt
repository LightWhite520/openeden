package io.openeden.runtime.time

class MutableRuntimeClock(
    var currentMs: Long,
) : RuntimeClock {
    override fun nowMs(): Long = currentMs
}
