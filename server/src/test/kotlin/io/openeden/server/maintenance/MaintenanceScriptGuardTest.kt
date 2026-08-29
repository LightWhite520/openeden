package io.openeden.server.maintenance

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MaintenanceScriptGuardTest {
    private val root = Path.of("..").toAbsolutePath().normalize()

    @Test
    fun `maintenance scripts invoke only the guarded live server entry`() {
        val export = script("export-production-conversation.ps1")
        val reset = script("reset-production-incarnation.ps1")

        listOf(export, reset).forEach { content ->
            assertTrue(content.contains("Invoke-RestMethod"))
            assertTrue(content.contains("Authorization"))
            assertTrue(content.contains("Resolve-CanonicalPath"))
            assertTrue(content.contains("8080"))
            assertFalse(content.contains(":server:runMaintenance"))
            assertFalse(content.contains("DatabasePath"))
            assertTrue(content.contains("schemaVersion -lt 23"))
        }
        assertTrue(export.contains("/api/v1/maintenance/incarnation/export"))
        assertTrue(reset.contains("/api/v1/maintenance/incarnation/reset"))
        assertFalse(reset.contains("VectorProjectionEnabled"))
        assertFalse(reset.contains("QdrantModelId"))
    }

    @Test
    fun `production verifier requires maintenance readiness schema and one active incarnation`() {
        val verifier = Files.readString(root.resolve("scripts/verify-production-runtime.ps1"))

        assertTrue(verifier.contains("/api/v1/maintenance/incarnation/readiness"))
        assertTrue(verifier.contains("schemaVersion -lt 23"))
        assertTrue(verifier.contains("activeIncarnationCount -ne 1"))
        assertTrue(verifier.contains("activeIncarnationId"))
        assertTrue(verifier.contains("incarnation_id"))
        assertTrue(verifier.contains("resetReadiness"))
        assertTrue(verifier.contains("8080"))
    }

    @Test
    fun `runbook documents secure configured root export capability`() {
        val runbook = Files.readString(root.resolve("docs/operations/companion-quality-production-rollout.md"))

        assertTrue(runbook.contains("direct child"))
        assertTrue(runbook.contains("SecureDirectoryStream"))
        assertTrue(runbook.contains("fail closed"))
        assertTrue(runbook.contains("schema version `23`"))
    }

    private fun script(name: String): String = Files.readString(root.resolve("scripts").resolve(name))
}
