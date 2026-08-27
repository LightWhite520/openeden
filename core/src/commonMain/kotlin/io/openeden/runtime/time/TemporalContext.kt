package io.openeden.runtime.time

data class TemporalContext(
    val exactTime: Long? = null,
    val elapsedBucket: String? = null,
    val dayPeriod: String? = null,
) {
    fun isEmpty(): Boolean = exactTime == null && elapsedBucket == null && dayPeriod == null
}
