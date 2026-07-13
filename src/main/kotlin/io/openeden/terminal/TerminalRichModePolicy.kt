package io.openeden.terminal

import org.jline.terminal.Terminal
import java.util.Locale

internal object TerminalRichModePolicy {
    fun isSupported(
        osName: String,
        terminalType: String,
        providerName: String?,
    ): Boolean {
        if (terminalType == Terminal.TYPE_DUMB || terminalType == Terminal.TYPE_DUMB_COLOR) return false
        val windows = osName.lowercase(Locale.ROOT).startsWith("windows")
        return !windows || providerName.equals("jni", ignoreCase = true)
    }
}
