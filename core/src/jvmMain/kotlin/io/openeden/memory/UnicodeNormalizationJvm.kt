package io.openeden.memory

import java.text.Normalizer

internal actual fun normalizeToNfc(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFC)
