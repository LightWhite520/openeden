package io.openeden.server.maintenance

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.UUID

/** All payload operations remain relative to pinned, non-reparse directory handles. */
class WindowsIncarnationExportPathGuard(exportRoot: Path) : IncarnationExportPathGuard {
    private val root = exportRoot.toAbsolutePath().normalize()

    class Handles internal constructor(
        internal val ancestors: List<WindowsNativeExportFiles.Handle>,
        internal val parent: WindowsNativeExportFiles.Handle,
        internal val staging: WindowsNativeExportFiles.Handle,
    ) : AutoCloseable {
        override fun close() {
            var failure: Throwable? = null
            (listOf(staging) + ancestors.asReversed()).forEach {
                try { it.close() } catch (error: Throwable) {
                    if (failure == null) failure = error else failure.addSuppressed(error)
                }
            }
            failure?.let { throw it }
        }
    }

    private fun pinRoot(): List<WindowsNativeExportFiles.Handle> {
        // Create missing ancestors through the same rooted walk; never create through a junction first.
        val handles = mutableListOf<WindowsNativeExportFiles.Handle>()
        try {
            handles += WindowsNativeExportFiles.openVolume(root.root)
            root.forEachIndexed { index, part ->
                handles += WindowsNativeExportFiles.child(handles.last(), part.toString(), directory = true,
                    writable = index == root.nameCount - 1, openOrCreateDirectory = true)
            }
            return handles
        } catch (failure: Throwable) {
            handles.asReversed().forEach { runCatching { it.close() }.exceptionOrNull()?.let(failure::addSuppressed) }
            throw failure
        }
    }

    override fun secureDirectoryHandlesAvailable(): Boolean = runCatching {
        val handles = pinRoot()
        try { WindowsNativeExportFiles.flush(handles.last()); true }
        finally { handles.asReversed().forEach { it.close() } }
    }.getOrDefault(false)

    override fun prepare(target: Path): PreparedIncarnationExportPaths {
        val absolute = target.toAbsolutePath().normalize()
        require(absolute.parent == root) { "Export target must be a direct child of the configured export root" }
        val name = absolute.fileName.toString()
        require(name.isNotBlank() && name.none { it in "\\/:\u0000" } && !name.endsWith('.') && !name.endsWith(' '))
        val ancestors = pinRoot()
        var staging: WindowsNativeExportFiles.Handle? = null
        try {
            val parent = ancestors.last()
            require(!Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) { "Export target already exists" }
            val stagingName = ".$name.staging-${UUID.randomUUID()}"
            staging = WindowsNativeExportFiles.child(parent, stagingName, directory = true,
                create = true, writable = true, deletable = true)
            val token = UUID.randomUUID().toString()
            WindowsNativeExportFiles.write(staging, MARKER, token.toByteArray())
            return PreparedIncarnationExportPaths(absolute, root, root.resolve(stagingName),
                WindowsNativeExportFiles.identity(parent), WindowsNativeExportFiles.identity(staging),
                publicationToken = token, nativeHandles = Handles(ancestors, parent, staging)).also(::revalidate)
        } catch (failure: Throwable) {
            (listOfNotNull(staging) + ancestors.asReversed()).forEach {
                runCatching { it.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            }
            throw failure
        }
    }

    override fun revalidate(paths: PreparedIncarnationExportPaths) {
        val handles = checkNotNull(paths.nativeHandles)
        check(paths.parent == root && paths.target.parent == root && paths.staging.parent == root)
        handles.ancestors.forEach { WindowsNativeExportFiles.identity(it) }
        check(WindowsNativeExportFiles.identity(handles.parent) == paths.parentFileKey) { "Export parent changed" }
        check(WindowsNativeExportFiles.identity(handles.staging) == paths.stagingFileKey) { "Export staging changed" }
    }

    override fun writeFile(paths: PreparedIncarnationExportPaths, name: String, bytes: ByteArray): Boolean {
        revalidate(paths)
        WindowsNativeExportFiles.write(checkNotNull(paths.nativeHandles).staging, name, bytes)
        return true
    }

    override fun flushDirectory(paths: PreparedIncarnationExportPaths, staging: Boolean): Boolean {
        val handles = checkNotNull(paths.nativeHandles)
        WindowsNativeExportFiles.flush(if (staging) handles.staging else handles.parent)
        return true
    }

    override fun publish(paths: PreparedIncarnationExportPaths, atomicMove: (Path, Path) -> Unit) {
        revalidate(paths)
        val handles = checkNotNull(paths.nativeHandles)
        WindowsNativeExportFiles.rename(handles.staging, handles.parent, paths.targetName.toString())
        revalidate(paths)
    }

    override fun finalizePublication(paths: PreparedIncarnationExportPaths) {
        revalidate(paths)
        WindowsNativeExportFiles.verifyAndDeleteMarker(checkNotNull(paths.nativeHandles).staging, MARKER, paths.publicationToken)
    }

    private companion object { const val MARKER = ".openeden-export-identity" }
}
