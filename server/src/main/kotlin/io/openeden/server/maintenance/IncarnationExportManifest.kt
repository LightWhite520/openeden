package io.openeden.server.maintenance

import kotlinx.serialization.Serializable

@Serializable
data class IncarnationExportManifest(
    val formatVersion: Int = CURRENT_FORMAT_VERSION,
    val status: IncarnationExportStatus,
    val incarnationId: String,
    val exportedAtMs: Long,
    val transcriptCount: Long,
    val memoryCount: Long,
    val relationshipEventCount: Long,
    val files: List<IncarnationExportFile>,
    val payloadSha256: String,
    val manifestSha256: String,
) {
    companion object {
        const val CURRENT_FORMAT_VERSION: Int = 1
    }
}

@Serializable
data class IncarnationExportFile(
    val name: String,
    val byteCount: Long,
    val sha256: String,
)

@Serializable
enum class IncarnationExportStatus {
    IN_PROGRESS,
    COMPLETED,
}
