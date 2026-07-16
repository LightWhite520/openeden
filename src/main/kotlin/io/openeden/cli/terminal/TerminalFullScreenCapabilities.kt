package io.openeden.cli.terminal

import org.jline.utils.InfoCmp.Capability

internal object TerminalFullScreenCapabilities {
    val required: List<Capability> = listOf(
        Capability.enter_ca_mode,
        Capability.exit_ca_mode,
    )
}
