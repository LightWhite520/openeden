package io.openeden.runtime.time

import kotlin.time.Clock

object SystemRuntimeClock : RuntimeClock {
    override fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()
}
