package io.openeden.server.evaluation

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

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
    fun `injected pipeline observes every authoritative turn and supplies exported records`() = runTest {
        val observer = RecordingEvaluationPipeline(RelationshipLongRunHarness.fakePipeline(EvaluationVariant.A))
        val scenario = RelationshipScenario.canonical(EvaluationVariant.A)
        val result = RelationshipLongRunHarness(DeterministicRuntimeClock(), observer).run(scenario)

        assertEquals(scenario.turns.size, observer.requests.size)
        assertEquals(result.turns.map(EvaluatedTurn::nowMs), observer.requests.map(EvaluationRequest::nowMs))
        assertTrue(result.observations.all { it.diagnostics.isNotEmpty() && it.trace.traceId.isNotBlank() })
        assertEquals(result.turns.size, result.relationshipEvents.size)
        assertEquals(result.turns.size, result.promptCacheManifest.size)
    }

    @Test
    fun `canonical fake makes required relationship behavior observable`() = runTest {
        val result = RelationshipLongRunHarness.fake(EvaluationVariant.A).run(RelationshipScenario.canonical(EvaluationVariant.A))
        val byTag = result.turns.associateBy { it.tags.singleOrNull { tag -> tag !in setOf("daily", "stranger", "negative") } }

        assertTrue(
            result.turns.filter { "negative" in it.tags }.all {
                it.userText.startsWith("要不要") && it.outcome == "invitation_declined_without_pressure"
            },
        )
        assertEquals("mutual_romance_reciprocated", byTag.getValue("hot-romance").outcome)
        assertEquals("boundary_respected", byTag.getValue("boundary").outcome)
        assertEquals("conflict_acknowledged", byTag.getValue("conflict").outcome)
        assertEquals("repair_completed", byTag.getValue("repair").outcome)
        assertEquals("silence_observed", byTag.getValue("silence").outcome)
        assertEquals("heartbeat_delivered", byTag.getValue("heartbeat").outcome)
        assertEquals("CONFESSED", byTag.getValue("confession").relationshipState)
        assertEquals("COUPLE", byTag.getValue("acceptance").relationshipState)
        assertEquals("COUPLE", byTag.getValue("restart").relationshipState)
    }

    @Test
    fun `variants produce distinct scenario and exported output fingerprints`() = runTest {
        val scenarioA = RelationshipScenario.canonical(EvaluationVariant.A)
        val scenarioB = RelationshipScenario.canonical(EvaluationVariant.B)
        val outputA = Files.createTempDirectory("relationship-evaluation-a")
        val outputB = Files.createTempDirectory("relationship-evaluation-b")

        val artifactsA = RelationshipLongRunHarness.fake(EvaluationVariant.A).run(scenarioA).exportTo(outputA)
        val artifactsB = RelationshipLongRunHarness.fake(EvaluationVariant.B).run(scenarioB).exportTo(outputB)

        assertNotEquals(scenarioA.fingerprint(), scenarioB.fingerprint())
        assertNotEquals(artifactsA.fingerprints, artifactsB.fingerprints)
    }

    @Test
    fun `variants expose a cache behavior difference independent of labels`() = runTest {
        val baseline = RelationshipLongRunHarness.fake(EvaluationVariant.A).run(RelationshipScenario.canonical(EvaluationVariant.A))
        val candidate = RelationshipLongRunHarness.fake(EvaluationVariant.B).run(RelationshipScenario.canonical(EvaluationVariant.B))

        assertTrue(baseline.cacheReadRate!! > candidate.cacheReadRate!!)
        assertNotEquals(
            baseline.promptCacheManifest.map { it.metrics.cachedInputTokens },
            candidate.promptCacheManifest.map { it.metrics.cachedInputTokens },
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

        val transcript = firstArtifacts.file("transcript.jsonl").jsonLines()
        val bio = firstArtifacts.file("bio.csv").csvRecords()
        val relationshipEvents = firstArtifacts.file("relationship-events.jsonl").jsonLines()
        val cacheManifest = firstArtifacts.file("prompt-cache-manifest.jsonl").jsonLines()

        assertEquals(first.turns.size, transcript.size)
        assertEquals(first.turns.size, bio.size)
        assertEquals(first.turns.size, relationshipEvents.size)
        assertEquals(first.turns.size, cacheManifest.size)
        assertTrue(
            bio.all {
                it.keys.containsAll(
                    setOf("turn", "now_ms", "L", "P", "E", "S", "tau", "V", "M", "F", "trace_stage", "diagnostics", "evolution_index", "omega"),
                ) && it.values.all(String::isNotBlank)
            },
        )
        assertTrue(bio.all { "evaluation_profile=" in it.getValue("diagnostics") && "outcome=" in it.getValue("diagnostics") })
        assertTrue(transcript.all { it.keys.containsAll(setOf("turn", "now_ms", "user_text", "response", "outcome", "relationship_state", "diagnostics")) })
        assertTrue(transcript.all { it.getValue("diagnostics").jsonObject.isNotEmpty() })
        assertTrue(relationshipEvents.all { it.keys.containsAll(setOf("turn", "now_ms", "events", "relationship_state", "evolution_index", "omega")) })
        assertTrue(cacheManifest.all { it.keys.containsAll(setOf("turn", "now_ms", "trace_id", "trace_stage", "input_tokens", "cached_input_tokens", "cache_read_rate")) })
    }

    @Test
    fun `exports the configured baseline directory for the PowerShell runner`() = runTest {
        val outputDirectory = System.getenv("OPENEDEN_EVALUATION_OUTPUT_DIRECTORY") ?: return@runTest

        val variant = EvaluationVariant.valueOf(System.getenv("OPENEDEN_EVALUATION_VARIANT") ?: "A")
        RelationshipLongRunHarness.fake(variant).run(RelationshipScenario.canonical(variant)).exportTo(java.nio.file.Path.of(outputDirectory))
    }

    private fun ExportedArtifacts.file(name: String) = files.single { it.fileName.toString() == name }

    private fun java.nio.file.Path.jsonLines() = Files.readAllLines(this).map { Json.parseToJsonElement(it).jsonObject }

    private fun java.nio.file.Path.csvRecords(): List<Map<String, String>> {
        val rows = Files.readAllLines(this).map(::parseCsvRow)
        val header = rows.first()
        return rows.drop(1).map { header.zip(it).toMap() }
    }

    private fun parseCsvRow(row: String): List<String> {
        val fields = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        while (index < row.length) {
            when (val character = row[index]) {
                '"' -> if (quoted && row.getOrNull(index + 1) == '"') {
                    field.append(character)
                    index++
                } else {
                    quoted = !quoted
                }
                ',' -> if (quoted) field.append(character) else {
                    fields += field.toString()
                    field.clear()
                }
                else -> field.append(character)
            }
            index++
        }
        fields += field.toString()
        return fields
    }

    private class RecordingEvaluationPipeline(
        private val delegate: RelationshipEvaluationPipeline,
    ) : RelationshipEvaluationPipeline {
        val requests = mutableListOf<EvaluationRequest>()

        override suspend fun evaluate(request: EvaluationRequest): EvaluationObservation {
            requests += request
            return delegate.evaluate(request)
        }
    }
}
