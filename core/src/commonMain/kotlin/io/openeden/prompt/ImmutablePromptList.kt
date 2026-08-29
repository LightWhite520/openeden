package io.openeden.prompt

internal expect fun <T> immutablePromptList(values: Collection<T>): List<T>
