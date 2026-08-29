package io.openeden.server.maintenance

import java.nio.file.Path

data class IncarnationExportRequest(
    val incarnationId: String,
    val targetDirectory: Path,
)

data class IncarnationExportResult(
    val directory: Path,
    val manifestPath: Path,
    val manifest: IncarnationExportManifest,
)
