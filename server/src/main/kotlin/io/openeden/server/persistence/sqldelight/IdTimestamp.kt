package io.openeden.server.persistence.sqldelight

/** Extracts the numeric segment immediately before a final suffix (for example `:raw`). */
internal fun createdAtMsFromId(id: String): Long {
    val withoutSuffix = id.substringBeforeLast(':')
    return withoutSuffix.substringAfterLast(':').toLongOrNull()
        ?: id.substringAfterLast(':').toLongOrNull()
        ?: 0L
}
