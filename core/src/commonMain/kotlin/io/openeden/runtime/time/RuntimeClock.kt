package io.openeden.runtime.time

fun interface RuntimeClock {
    fun nowMs(): Long
}
