package io.openeden.terminal

import org.jline.terminal.Terminal
import java.util.Locale

internal object TerminalRichModePolicy {
    fun isSupported(
        osName: String,
        terminalType: String,
        providerName: String?,
        warningSink: (String) -> Unit = {},
    ): Boolean {
        val windows = osName.lowercase(Locale.ROOT).startsWith("windows")
        val jniProvider = providerName.equals("jni", ignoreCase = true)
        if (windows && !jniProvider) warningSink(JNI_FALLBACK_WARNING)
        if (terminalType == Terminal.TYPE_DUMB || terminalType == Terminal.TYPE_DUMB_COLOR) return false
        return !windows || jniProvider
    }

    const val JNI_FALLBACK_WARNING = "JLine JNI terminal unavailable; using plain line mode."
}
