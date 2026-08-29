package io.openeden.server.maintenance

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

internal data class IncarnationExportSnapshot(
    val incarnationId: String,
    val files: List<IncarnationExportSnapshotFile>,
    val transcriptCount: Long,
    val memoryCount: Long,
    val relationshipEventCount: Long,
) {
    val payloadSha256: String = IncarnationExportIntegrity.payloadSha256(files)
}

internal data class IncarnationExportSnapshotFile(
    val name: String,
    val bytes: ByteArray,
) {
    val sha256: String = IncarnationExportIntegrity.sha256(bytes)
}

internal object IncarnationExportIntegrity {
    val compactJson = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    val manifestJson = Json(compactJson) {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    fun manifestSha256(manifest: IncarnationExportManifest): String = sha256(
        compactJson.encodeToString(manifest.copy(manifestSha256 = "")).encodeToByteArray(),
    )

    fun payloadSha256(files: List<IncarnationExportSnapshotFile>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        files.sortedBy { it.name }.forEach { file ->
            digest.update(file.name.encodeToByteArray())
            digest.update(0)
            digest.update(file.bytes)
        }
        return digest.digest().toHex()
    }

    fun payloadSha256FromBytes(files: List<Pair<String, ByteArray>>): String = payloadSha256(
        files.map { (name, bytes) -> IncarnationExportSnapshotFile(name, bytes) },
    )

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .toHex()

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
}

enum class IncarnationExportVerificationFailure {
    INCOMPLETE,
    INVALID_HASH,
}

class IncarnationExportVerificationException(
    val failure: IncarnationExportVerificationFailure,
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)
