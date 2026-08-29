package io.openeden.prompt

import java.util.Collections

internal actual fun <T> immutablePromptList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))
