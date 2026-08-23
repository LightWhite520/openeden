package io.openeden.memory

import kotlin.JsFun
import kotlin.js.ExperimentalWasmJsInterop

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("value => value.normalize('NFC')")
private external fun normalizeWithJavaScript(value: String): String

internal actual fun normalizeToNfc(value: String): String = normalizeWithJavaScript(value)
