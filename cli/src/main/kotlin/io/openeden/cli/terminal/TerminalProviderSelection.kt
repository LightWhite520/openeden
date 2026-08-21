package io.openeden.cli.terminal

import java.util.Locale

internal object TerminalProviderSelection {
    fun forOs(osName: String): String =
        if (osName.lowercase(Locale.ROOT).startsWith("windows")) "jni" else "jni,exec"
}
