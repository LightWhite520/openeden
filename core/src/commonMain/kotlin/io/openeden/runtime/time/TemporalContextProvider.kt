package io.openeden.runtime.time

class TemporalContextProvider(
    private val clock: RuntimeClock,
) {
    fun forTurn(input: String, lastActivityMs: Long?): TemporalContext {
        val nowMs = clock.nowMs()
        return TemporalContext(
            exactTime = nowMs.takeIf { input.requestsExactTime() },
            elapsedBucket = lastActivityMs?.let { activityMs -> elapsedBucket(nowMs - activityMs) },
            dayPeriod = dayPeriod(nowMs),
        )
    }

    private fun elapsedBucket(elapsedMs: Long): String = when {
        elapsedMs <= RECENT_WINDOW_MS -> "recent"
        elapsedMs <= HOUR_MS -> "within_hour"
        elapsedMs <= DAY_MS -> "today"
        else -> "long_absence"
    }

    private fun dayPeriod(nowMs: Long): String {
        val hour = ((nowMs / HOUR_MS + SHANGHAI_UTC_OFFSET_HOURS) % HOURS_PER_DAY)
            .let { if (it < 0L) it + HOURS_PER_DAY else it }
        return when (hour) {
            in 0L..4L -> "late_night"
            in 5L..10L -> "morning"
            in 11L..16L -> "afternoon"
            else -> "evening"
        }
    }

    private fun String.requestsExactTime(): Boolean {
        val normalized = lowercase()
        return exactTimeMarkers.any(normalized::contains)
    }

    private companion object {
        const val RECENT_WINDOW_MS = 5L * 60L * 1_000L
        const val HOUR_MS = 60L * 60L * 1_000L
        const val DAY_MS = 24L * HOUR_MS
        const val HOURS_PER_DAY = 24L
        const val SHANGHAI_UTC_OFFSET_HOURS = 8L

        val exactTimeMarkers = listOf(
            "几点",
            "时间",
            "日期",
            "星期",
            "周几",
            "几号",
            "what time",
            "what date",
            "what day",
            "current time",
        )
    }
}
