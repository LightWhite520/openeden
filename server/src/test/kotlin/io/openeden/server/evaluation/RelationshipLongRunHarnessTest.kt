package io.openeden.server.evaluation

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class RelationshipLongRunHarnessTest {
    @Test
    fun `scenario advances one authoritative virtual clock and exports every turn`() = runTest {
        val result = RelationshipLongRunHarness.fake().run(RelationshipScenario.canonical())

        assertTrue(result.turns.size in 120..200)
        assertTrue(result.turns.zipWithNext().all { (a, b) -> a.nowMs <= b.nowMs })
        assertEquals(result.turns.size, result.bioSnapshots.size)
    }

    @Test
    fun `canonical scenario covers the required long run relationship events`() {
        val tags = RelationshipScenario.canonical().turns.flatMap { it.tags }.toSet()

        assertTrue(RelationshipScenario.canonical().turns.count { "negative" in it.tags } >= 20)
        assertTrue(
            setOf(
                "confession",
                "acceptance",
                "restart",
                "hot-romance",
                "chores",
                "silence",
                "heartbeat",
                "boundary",
                "conflict",
                "repair",
            ).all(tags::contains),
        )
    }

    @Test
    fun `fake baseline writes stable fingerprints for every required artifact`() = runTest {
        val firstOutput = Files.createTempDirectory("relationship-evaluation-first")
        val secondOutput = Files.createTempDirectory("relationship-evaluation-second")

        val first = RelationshipLongRunHarness.fake().run(RelationshipScenario.canonical())
        val second = RelationshipLongRunHarness.fake().run(RelationshipScenario.canonical())
        val firstArtifacts = first.exportTo(firstOutput)
        val secondArtifacts = second.exportTo(secondOutput)

        assertEquals(firstArtifacts.fingerprints, secondArtifacts.fingerprints)
        assertEquals(
            setOf(
                "transcript.jsonl",
                "bio.csv",
                "relationship-events.jsonl",
                "prompt-cache-manifest.jsonl",
                "evaluation-report.md",
            ),
            firstArtifacts.files.map { it.fileName.toString() }.toSet(),
        )
        assertTrue(firstArtifacts.files.all(Files::exists))
    }

    @Test
    fun `exports the configured baseline directory for the PowerShell runner`() = runTest {
        val outputDirectory = System.getenv("OPENEDEN_EVALUATION_OUTPUT_DIRECTORY") ?: return@runTest

        RelationshipLongRunHarness.fake().run(RelationshipScenario.canonical()).exportTo(java.nio.file.Path.of(outputDirectory))
    }
}
