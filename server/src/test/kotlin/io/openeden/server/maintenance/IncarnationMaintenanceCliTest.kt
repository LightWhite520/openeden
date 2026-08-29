package io.openeden.server.maintenance

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse

class IncarnationMaintenanceCliTest {
    @Test
    fun `server exposes no standalone maintenance task or local database entry point`() {
        val repositoryRoot = Path.of("..").toAbsolutePath().normalize()
        val serverBuild = Files.readString(repositoryRoot.resolve("server/build.gradle.kts"))
        val standaloneEntry = repositoryRoot.resolve(
            "server/src/main/kotlin/io/openeden/server/maintenance/IncarnationMaintenanceCli.kt",
        )

        assertFalse(serverBuild.contains("runMaintenance"))
        assertFalse(Files.exists(standaloneEntry))
    }
}
