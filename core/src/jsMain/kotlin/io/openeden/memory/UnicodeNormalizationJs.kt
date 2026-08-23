package io.openeden.memory

import kotlin.js.asDynamic

internal actual fun normalizeToNfc(value: String): String =
    value.asDynamic().normalize("NFC") as String
