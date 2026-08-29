package io.openeden.server.maintenance

import io.openeden.runtime.incarnation.IncarnationTurnGate
import io.openeden.server.persistence.sqldelight.SqlDelightIncarnationMaintenanceRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

class IncarnationDataExporter(
    private val repository: SqlDelightIncarnationMaintenanceRepository,
    private val mutationGate: IncarnationTurnGate,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val fileIoDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val durability: IncarnationExportDurability = NioIncarnationExportDurability,
    exportRoot: Path? = null,
    private val pathGuard: IncarnationExportPathGuard = SecureNioIncarnationExportPathGuard(
        requireNotNull(exportRoot) { "Production export requires a configured export root" },
    ),
) {
    suspend fun export(request: IncarnationExportRequest): IncarnationExportResult {
        require(request.incarnationId.isNotBlank()) { "incarnationId must not be blank" }
        val snapshot = mutationGate.withIncarnation(request.incarnationId) {
            repository.exportSnapshot(request.incarnationId)
        }
        return withContext(fileIoDispatcher) {
            writeDurableExport(request.targetDirectory, snapshot)
        }
    }

    suspend fun verify(manifestPath: Path): IncarnationExportManifest = withContext(fileIoDispatcher) {
        val manifest = readManifest(manifestPath)
        if (manifest.status != IncarnationExportStatus.COMPLETED) {
            throw IncarnationExportVerificationException(
                IncarnationExportVerificationFailure.INCOMPLETE,
                "Export manifest is not completed",
            )
        }
        verifyIntegrity(manifestPath, manifest)
        manifest
    }

    internal suspend fun readUnverified(manifestPath: Path): IncarnationExportManifest = withContext(fileIoDispatcher) {
        readManifest(manifestPath)
    }

    private fun writeDurableExport(
        requestedTarget: Path,
        snapshot: IncarnationExportSnapshot,
    ): IncarnationExportResult {
        val paths = pathGuard.prepare(requestedTarget)
        return paths.use {
            val target = paths.target
            val parent = paths.parent
            val staging = paths.staging
            pathGuard.revalidate(paths)

            val files = snapshot.files.sortedBy { it.name }.map { file ->
                require(file.name == Path.of(file.name).fileName.toString()) { "Invalid export file name: ${file.name}" }
                forceWrite(paths, file.name, file.bytes)
                IncarnationExportFile(file.name, file.bytes.size.toLong(), file.sha256)
            }
            val unsigned = IncarnationExportManifest(
                status = IncarnationExportStatus.COMPLETED,
                incarnationId = snapshot.incarnationId,
                exportedAtMs = nowMs(),
                transcriptCount = snapshot.transcriptCount,
                memoryCount = snapshot.memoryCount,
                relationshipEventCount = snapshot.relationshipEventCount,
                files = files,
                payloadSha256 = snapshot.payloadSha256,
                manifestSha256 = "",
            )
            val manifest = unsigned.copy(manifestSha256 = IncarnationExportIntegrity.manifestSha256(unsigned))
            pathGuard.revalidate(paths)
            forceWrite(
                paths,
                MANIFEST_FILE_NAME,
                (IncarnationExportIntegrity.manifestJson.encodeToString(manifest) + "\n").encodeToByteArray(),
            )
            forceDirectory(paths.stagingHandle, staging)
            forceDirectory(paths.parentHandle, parent)
            pathGuard.publish(paths, durability::atomicMove)
            forceDirectory(paths.stagingHandle, target)
            forceDirectory(paths.parentHandle, parent)
            pathGuard.finalizePublication(paths)
            forceDirectory(paths.stagingHandle, target)
            forceDirectory(paths.parentHandle, parent)
            val manifestPath = target.resolve(MANIFEST_FILE_NAME)
            IncarnationExportResult(target, manifestPath, manifest)
        }
    }

    private fun forceWrite(paths: PreparedIncarnationExportPaths, name: String, bytes: ByteArray) {
        pathGuard.revalidate(paths)
        val stagingHandle = paths.stagingHandle
        if (stagingHandle == null) {
            durability.forceWrite(paths.staging.resolve(name), bytes)
            return
        }
        val options = setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
        stagingHandle.newByteChannel(Path.of(name), options).use { channel ->
            val file = channel as? FileChannel ?: throw IncarnationExportCapabilityException(
                "Secure export filesystem did not provide a durable file channel",
            )
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) file.write(buffer)
            file.force(true)
        }
    }

    private fun forceDirectory(handle: java.nio.file.SecureDirectoryStream<Path>?, path: Path) {
        if (handle == null) {
            durability.forceDirectory(path)
            return
        }
        handle.newByteChannel(Path.of("."), setOf(StandardOpenOption.READ)).use { channel ->
            val directory = channel as? FileChannel ?: throw IncarnationExportCapabilityException(
                "Secure export filesystem did not provide a durable directory channel",
            )
            directory.force(true)
        }
    }

    private fun verifyIntegrity(manifestPath: Path, manifest: IncarnationExportManifest) {
        try {
            require(manifest.formatVersion == IncarnationExportManifest.CURRENT_FORMAT_VERSION)
            require(manifest.manifestSha256 == IncarnationExportIntegrity.manifestSha256(manifest))
            val directory = manifestPath.toAbsolutePath().normalize().parent
                ?: error("Manifest must have a parent directory")
            val expectedNames = manifest.files.map { it.name }.toSet()
            require(expectedNames.size == manifest.files.size)
            require(expectedNames.all { name -> name == Path.of(name).fileName.toString() })
            val actualNames = Files.list(directory).use { paths ->
                paths.filter(Files::isRegularFile).map { it.fileName.toString() }.toList().toSet()
            }
            require(actualNames == expectedNames + MANIFEST_FILE_NAME)
            val payload = manifest.files.sortedBy { it.name }.map { expected ->
                val bytes = Files.readAllBytes(directory.resolve(expected.name))
                require(bytes.size.toLong() == expected.byteCount)
                require(IncarnationExportIntegrity.sha256(bytes) == expected.sha256)
                expected.name to bytes
            }
            require(IncarnationExportIntegrity.payloadSha256FromBytes(payload) == manifest.payloadSha256)
        } catch (failure: Exception) {
            if (failure is IncarnationExportVerificationException) throw failure
            throw IncarnationExportVerificationException(
                IncarnationExportVerificationFailure.INVALID_HASH,
                "Export manifest or payload integrity verification failed",
                failure,
            )
        }
    }

    private fun readManifest(manifestPath: Path): IncarnationExportManifest = try {
        val resolved = manifestPath.toAbsolutePath().normalize()
        require(resolved.fileName.toString() == MANIFEST_FILE_NAME)
        require(Files.isRegularFile(resolved))
        IncarnationExportIntegrity.manifestJson.decodeFromString(Files.readString(resolved))
    } catch (failure: Exception) {
        throw IncarnationExportVerificationException(
            IncarnationExportVerificationFailure.INVALID_HASH,
            "Export manifest could not be read",
            failure,
        )
    }

    private companion object {
        const val MANIFEST_FILE_NAME = "manifest.json"
    }
}
