package io.openeden.prompt

/** Formats runtime time without depending on the host machine's timezone. */
object PromptTime {
    private const val SHANGHAI_OFFSET_MILLIS = 8L * 60L * 60L * 1_000L
    private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1_000L

    fun format(epochMillis: Long): String {
        val localMillis = epochMillis + SHANGHAI_OFFSET_MILLIS
        val epochDay = floorDiv(localMillis, MILLIS_PER_DAY)
        val dayMillis = localMillis - epochDay * MILLIS_PER_DAY
        val hour = dayMillis / (60L * 60L * 1_000L)
        val minute = (dayMillis / (60L * 1_000L)) % 60L
        val date = civilDate(epochDay)
        return buildString {
            appendPadded(date.year, 4)
            append('-')
            appendPadded(date.month, 2)
            append('-')
            appendPadded(date.day, 2)
            append(' ')
            appendPadded(hour, 2)
            append(':')
            appendPadded(minute, 2)
        }
    }

    private fun floorDiv(value: Long, divisor: Long): Long =
        if (value >= 0L) value / divisor else (value - divisor + 1L) / divisor

    private fun civilDate(epochDay: Long): CivilDate {
        val shifted = epochDay + 719_468L
        val era = if (shifted >= 0L) shifted / 146_097L else (shifted - 146_096L) / 146_097L
        val dayOfEra = shifted - era * 146_097L
        val yearOfEra = (dayOfEra - dayOfEra / 1_460L + dayOfEra / 36_524L - dayOfEra / 146_096L) / 365L
        var year = yearOfEra + era * 400L
        val dayOfYear = dayOfEra - (365L * yearOfEra + yearOfEra / 4L - yearOfEra / 100L)
        val monthPart = (5L * dayOfYear + 2L) / 153L
        val day = dayOfYear - (153L * monthPart + 2L) / 5L + 1L
        val month = monthPart + if (monthPart < 10L) 3L else -9L
        year += if (month <= 2L) 1L else 0L
        return CivilDate(year, month, day)
    }

    private fun StringBuilder.appendPadded(value: Long, width: Int) {
        val text = value.toString()
        repeat((width - text.length).coerceAtLeast(0)) { append('0') }
        append(text)
    }

    private data class CivilDate(val year: Long, val month: Long, val day: Long)
}
