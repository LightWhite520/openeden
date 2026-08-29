package io.openeden.server.maintenance

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.StandardOpenOption
import java.util.UUID

data class PreparedIncarnationExportPaths(
    val target: Path,
    val parent: Path,
    val staging: Path,
    internal val parentFileKey: Any?,
    internal val stagingFileKey: Any?,
    internal val parentHandle: SecureDirectoryStream<Path>? = null,
    internal val stagingHandle: SecureDirectoryStream<Path>? = null,
    internal val targetName: Path = target.fileName,
    internal val stagingName: Path = staging.fileName,
    internal val publicationToken: String,
) : AutoCloseable {
    override fun close() {
        stagingHandle?.close()
        parentHandle?.close()
    }
}

interface IncarnationExportPathGuard {
    fun prepare(target: Path): PreparedIncarnationExportPaths
    fun revalidate(paths: PreparedIncarnationExportPaths)

    fun publish(paths: PreparedIncarnationExportPaths, atomicMove: (Path, Path) -> Unit) {
        revalidate(paths)
        atomicMove(paths.staging, paths.target)
        try {
            val marker = paths.target.resolve(PUBLICATION_MARKER)
            val token = Files.newByteChannel(
                marker,
                setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
            ).use(::readToken)
            check(token == paths.publicationToken) {
                "Published export target identity marker differs from the prepared staging directory"
            }
            requirePublishedIdentityIfAvailable(paths.stagingFileKey, attributes(paths.target).fileKey())
        } catch (failure: Throwable) {
            runCatching { Files.deleteIfExists(paths.target) }
                .exceptionOrNull()
                ?.let(failure::addSuppressed)
            if (failure is IllegalStateException) throw failure
            throw IllegalStateException("Published export target identity could not be verified", failure)
        }
    }

    fun finalizePublication(paths: PreparedIncarnationExportPaths) {
        try {
            val marker = paths.target.resolve(PUBLICATION_MARKER)
            val token = Files.newByteChannel(
                marker,
                setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
            ).use(::readToken)
            check(token == paths.publicationToken) {
                "Published export target identity marker differs from the prepared staging directory"
            }
            requirePublishedIdentityIfAvailable(paths.stagingFileKey, attributes(paths.target).fileKey())
            Files.delete(marker)
            requirePublishedIdentityIfAvailable(paths.stagingFileKey, attributes(paths.target).fileKey())
        } catch (failure: Throwable) {
            if (failure is IllegalStateException) throw failure
            throw IllegalStateException("Published export target identity could not be finalized", failure)
        }
    }
}

object NioIncarnationExportPathGuard : IncarnationExportPathGuard {
    override fun prepare(target: Path): PreparedIncarnationExportPaths {
        val absolute = target.toAbsolutePath().normalize()
        val requestedParent = absolute.parent ?: error("Export target must have a parent directory")
        Files.createDirectories(requestedParent)
        val parent = requestedParent.toRealPath()
        val canonicalTarget = parent.resolve(absolute.fileName.toString())
        require(!Files.exists(canonicalTarget, LinkOption.NOFOLLOW_LINKS)) {
            "Export target already exists: $canonicalTarget"
        }
        val staging = parent.resolve("${absolute.fileName}.staging")
        require(!Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
            "Export staging target already exists: $staging"
        }
        Files.createDirectory(staging)
        require(!Files.isSymbolicLink(staging)) { "Export staging directory must not be a link" }
        val publicationToken = UUID.randomUUID().toString()
        Files.writeString(
            staging.resolve(PUBLICATION_MARKER),
            publicationToken,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        )
        return PreparedIncarnationExportPaths(
            target = canonicalTarget,
            parent = parent,
            staging = staging,
            parentFileKey = attributes(parent).fileKey(),
            stagingFileKey = attributes(staging).fileKey(),
            publicationToken = publicationToken,
        ).also(::revalidate)
    }

    override fun revalidate(paths: PreparedIncarnationExportPaths) {
        require(paths.parent.toRealPath() == paths.parent) { "Export parent identity changed" }
        require(paths.staging.parent == paths.parent) { "Export staging escaped its verified parent" }
        require(!Files.isSymbolicLink(paths.staging) && Files.isDirectory(paths.staging, LinkOption.NOFOLLOW_LINKS)) {
            "Export staging identity changed"
        }
        requireSameIdentity(paths.parentFileKey, attributes(paths.parent).fileKey(), "parent")
        requireSameIdentity(paths.stagingFileKey, attributes(paths.staging).fileKey(), "staging")
    }

    private fun attributes(path: Path): BasicFileAttributes =
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)

    private fun requireSameIdentity(expected: Any?, actual: Any?, label: String) {
        if (expected != null || actual != null) require(expected == actual) { "Export $label filesystem identity changed" }
    }
}

class IncarnationExportCapabilityException(message: String) : IllegalStateException(message)

class SecureNioIncarnationExportPathGuard(exportRoot: Path) : IncarnationExportPathGuard {
    private val root = Files.createDirectories(exportRoot.toAbsolutePath().normalize()).toRealPath()

    override fun prepare(target: Path): PreparedIncarnationExportPaths {
        val absolute = target.toAbsolutePath().normalize()
        require(absolute.parent?.toRealPath() == root) {
            "Export target must be a direct child of the configured export root"
        }
        val targetName = absolute.fileName
        val opened = Files.newDirectoryStream(root)
        val parent = opened as? SecureDirectoryStream<Path> ?: run {
            opened.close()
            throw IncarnationExportCapabilityException(
                "Configured export filesystem does not provide secure directory handles; export is disabled",
            )
        }
        try {
            require(!Files.exists(root.resolve(targetName), LinkOption.NOFOLLOW_LINKS)) {
                "Export target already exists: ${root.resolve(targetName)}"
            }
            val stagingName = Path.of(".${targetName}.staging-${UUID.randomUUID()}")
            val stagingPath = root.resolve(stagingName)
            Files.createDirectory(stagingPath)
            val publicationToken = UUID.randomUUID().toString()
            Files.writeString(
                stagingPath.resolve(PUBLICATION_MARKER),
                publicationToken,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            )
            val expected = requireIdentityCapability(attributes(stagingPath).fileKey(), "staging")
            val staging = parent.newDirectoryStream(stagingName, LinkOption.NOFOLLOW_LINKS)
            val openedKey = staging.getFileAttributeView(java.nio.file.attribute.BasicFileAttributeView::class.java)
                .readAttributes().fileKey()
            requireSameIdentity(expected, openedKey, "staging")
            return PreparedIncarnationExportPaths(
                target = root.resolve(targetName),
                parent = root,
                staging = stagingPath,
                parentFileKey = requireIdentityCapability(attributes(root).fileKey(), "parent"),
                stagingFileKey = expected,
                parentHandle = parent,
                stagingHandle = staging,
                targetName = targetName,
                stagingName = stagingName,
                publicationToken = publicationToken,
            ).also(::revalidate)
        } catch (failure: Throwable) {
            parent.close()
            throw failure
        }
    }

    override fun revalidate(paths: PreparedIncarnationExportPaths) {
        require(paths.parent == root && root.toRealPath() == root) { "Export parent identity changed" }
        require(paths.staging.parent == root) { "Export staging escaped its configured root" }
        require(!Files.isSymbolicLink(paths.staging) && Files.isDirectory(paths.staging, LinkOption.NOFOLLOW_LINKS)) {
            "Export staging identity changed"
        }
        requireSameIdentity(paths.parentFileKey, attributes(root).fileKey(), "parent")
        requireSameIdentity(paths.stagingFileKey, attributes(paths.staging).fileKey(), "staging")
        val handleKey = checkNotNull(paths.stagingHandle)
            .getFileAttributeView(java.nio.file.attribute.BasicFileAttributeView::class.java)
            .readAttributes().fileKey()
        requireSameIdentity(paths.stagingFileKey, handleKey, "staging handle")
    }

    override fun publish(paths: PreparedIncarnationExportPaths, atomicMove: (Path, Path) -> Unit) {
        val parent = checkNotNull(paths.parentHandle)
        revalidate(paths)
        parent.move(paths.stagingName, parent, paths.targetName)
        parent.newDirectoryStream(paths.targetName, LinkOption.NOFOLLOW_LINKS).use { target ->
            verifyPublishedHandle(target, paths)
        }
    }

    override fun finalizePublication(paths: PreparedIncarnationExportPaths) {
        val parent = checkNotNull(paths.parentHandle)
        try {
            parent.newDirectoryStream(paths.targetName, LinkOption.NOFOLLOW_LINKS).use { target ->
                verifyPublishedHandle(target, paths)
                target.deleteFile(Path.of(PUBLICATION_MARKER))
            }
            parent.newDirectoryStream(paths.targetName, LinkOption.NOFOLLOW_LINKS).use { target ->
                val actual = target.getFileAttributeView(BasicFileAttributeView::class.java)
                    .readAttributes().fileKey()
                requirePublishedIdentity(paths.stagingFileKey, actual)
            }
        } catch (failure: Throwable) {
            if (failure is IllegalStateException) throw failure
            throw IllegalStateException("Published export target identity could not be finalized", failure)
        }
    }

    private fun verifyPublishedHandle(
        target: SecureDirectoryStream<Path>,
        paths: PreparedIncarnationExportPaths,
    ) {
        val marker = Path.of(PUBLICATION_MARKER)
        val token = target.newByteChannel(marker, setOf(StandardOpenOption.READ)).use(::readToken)
        check(token == paths.publicationToken) {
            "Published export target identity marker differs from the prepared staging directory"
        }
        val actual = target.getFileAttributeView(BasicFileAttributeView::class.java)
            .readAttributes().fileKey()
        requirePublishedIdentity(paths.stagingFileKey, actual)
    }

    private fun attributes(path: Path): BasicFileAttributes =
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)

    private fun requireSameIdentity(expected: Any?, actual: Any?, label: String) {
        if (expected != null || actual != null) require(expected == actual) { "Export $label filesystem identity changed" }
    }
}

private fun attributes(path: Path): BasicFileAttributes =
    Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)

private fun requireIdentityCapability(fileKey: Any?, label: String): Any =
    fileKey ?: throw IncarnationExportCapabilityException(
        "Export filesystem does not expose a stable $label identity; export is disabled",
    )

private fun requirePublishedIdentity(expected: Any?, actual: Any?) {
    val expectedKey = requireIdentityCapability(expected, "staging")
    val actualKey = requireIdentityCapability(actual, "published target")
    check(expectedKey == actualKey) { "Published export target identity differs from the prepared staging directory" }
}

private fun requirePublishedIdentityIfAvailable(expected: Any?, actual: Any?) {
    if (expected != null || actual != null) {
        check(expected == actual) { "Published export target identity differs from the prepared staging directory" }
    }
}

private fun readToken(channel: java.nio.channels.SeekableByteChannel): String {
    val buffer = ByteBuffer.allocate(128)
    while (channel.read(buffer) > 0) {
        check(buffer.hasRemaining()) { "Export identity marker is too large" }
    }
    buffer.flip()
    return StandardCharsets.UTF_8.decode(buffer).toString()
}

private const val PUBLICATION_MARKER = ".openeden-export-identity"
