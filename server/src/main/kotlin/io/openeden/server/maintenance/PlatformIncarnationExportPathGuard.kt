package io.openeden.server.maintenance

import java.nio.file.Path

internal fun platformIncarnationExportPathGuard(root: Path): IncarnationExportPathGuard =
    if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        WindowsIncarnationExportPathGuard(root)
    } else {
        SecureNioIncarnationExportPathGuard(root)
    }
