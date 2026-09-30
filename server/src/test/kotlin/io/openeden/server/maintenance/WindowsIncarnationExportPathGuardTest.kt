package io.openeden.server.maintenance

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsIncarnationExportPathGuardTest {
    private fun withRoot(test: (Path) -> Unit) {
        if (!System.getProperty("os.name").startsWith("Windows")) return
        val root = Files.createTempDirectory("openeden-native-export-")
        try { test(root) } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun `missing configured root is created without traversing junction ancestors`() = withRoot { root ->
        val configured = root.resolve("new/nested/exports")
        val guard = WindowsIncarnationExportPathGuard(configured)
        assertTrue(guard.secureDirectoryHandlesAvailable())
        guard.prepare(configured.resolve("result")).use { paths ->
            guard.publish(paths) { _, _ -> error("Must use native rename") }
            guard.finalizePublication(paths)
        }
        assertTrue(Files.isDirectory(configured.resolve("result")))
    }

    @Test
    fun `rooted export flushes publishes and releases all native handles`() = withRoot { root ->
        val guard = WindowsIncarnationExportPathGuard(root)
        assertTrue(guard.secureDirectoryHandlesAvailable())
        assertTrue(Files.list(root).use { it.findAny().isEmpty })
        guard.prepare(root.resolve("result")).use { paths ->
            assertFails { Files.move(paths.staging, root.resolve("swapped")) }
            assertFails { Files.move(root, root.resolveSibling(root.fileName.toString() + "-moved")) }
            guard.writeFile(paths, "payload.json", "payload".toByteArray())
            assertFails { guard.writeFile(paths, "../escaped", byteArrayOf(1)) }
            assertFails { guard.writeFile(paths, "payload.json", "overwrite".toByteArray()) }
            guard.flushDirectory(paths, true)
            guard.flushDirectory(paths, false)
            guard.publish(paths) { _, _ -> error("Must use native rooted rename") }
            guard.finalizePublication(paths)
            guard.flushDirectory(paths, true)
            guard.flushDirectory(paths, false)
        }
        assertEquals("payload", Files.readString(root.resolve("result/payload.json")))
        assertFalse(Files.exists(root.resolve("result/.openeden-export-identity")))
        assertFails { guard.prepare(root.resolve("result")) }
        assertFails { guard.prepare(root.parent.resolve("outside")) }
        Files.move(root.resolve("result"), root.resolve("released"))
    }

    @Test
    fun `target created after prepare is never replaced`() = withRoot { root ->
        val guard = WindowsIncarnationExportPathGuard(root)
        guard.prepare(root.resolve("result")).use { paths ->
            Files.createDirectory(paths.target)
            Files.writeString(paths.target.resolve("original"), "keep")
            assertFails { guard.publish(paths) { _, _ -> error("Must use native rename") } }
            assertEquals("keep", Files.readString(paths.target.resolve("original")))
        }
    }

    @Test
    fun `junction export root is rejected without touching its destination`() = withRoot { root ->
        val outside = Files.createDirectory(root.resolve("outside"))
        val junction = root.resolve("junction")
        val process = ProcessBuilder("cmd.exe", "/c", "mklink", "/J", junction.toString(), outside.toString())
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), output)
        try {
            val guard = WindowsIncarnationExportPathGuard(junction)
            assertFalse(guard.secureDirectoryHandlesAvailable())
            assertFails { guard.prepare(junction.resolve("export")) }
            val nested = WindowsIncarnationExportPathGuard(junction.resolve("must-not-create"))
            assertFalse(nested.secureDirectoryHandlesAvailable())
            assertTrue(Files.list(outside).use { it.findAny().isEmpty })
        } finally { Files.delete(junction) }
    }
}
