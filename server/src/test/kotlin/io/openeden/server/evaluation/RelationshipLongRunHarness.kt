package io.openeden.server.evaluation

import io.openeden.bio.BioVector
import io.openeden.llm.LlmCacheMetrics
import io.openeden.runtime.time.RuntimeClock
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class EvaluationVariant { A, B }

data class EvaluationRequest(
    val index: Int,
    val nowMs: Long,
    val scenarioTurn: ScenarioTurn,
    val variant: EvaluationVariant,
)

data class EvaluatedTurn(
    val index: Int,
    val nowMs: Long,
    val userText: String,
    val tags: Set<String>,
    val response: String,
    val outcome: String,
    val relationshipState: String,
)

data class RelationshipEvent(
    val turnIndex: Int,
    val nowMs: Long,
    val events: Set<String>,
    val relationshipState: String,
)

data class EvaluationTrace(
    val traceId: String,
    val stage: String,
)

data class EvaluationState(
    val relationshipState: String,
    val evolutionIndex: Long,
    val omega: Float,
)

data class EvaluationObservation(
    val transcript: EvaluatedTurn,
    val bio: BioVector,
    val diagnostics: Map<String, String>,
    val trace: EvaluationTrace,
    val cacheMetrics: LlmCacheMetrics,
    val relationshipEvent: RelationshipEvent,
    val state: EvaluationState,
)

fun interface RelationshipEvaluationPipeline {
    suspend fun evaluate(request: EvaluationRequest): EvaluationObservation
}

data class PromptCacheManifestEntry(
    val turnIndex: Int,
    val nowMs: Long,
    val traceId: String,
    val metrics: LlmCacheMetrics,
)

data class LongRunResult(
    val observations: List<EvaluationObservation>,
    val turns: List<EvaluatedTurn>,
    val bioSnapshots: List<BioVector>,
    val cacheReadRate: Double?,
    val relationshipEvents: List<RelationshipEvent>,
    val promptCacheManifest: List<PromptCacheManifestEntry>,
) {
    suspend fun exportTo(outputDirectory: Path): ExportedArtifacts = withContext(Dispatchers.IO) {
        Files.createDirectories(outputDirectory)
        val files = listOf(
            outputDirectory.resolve("transcript.jsonl") to observations.joinToString("\n") { observation ->
                val turn = observation.transcript
                "{\"turn\":${turn.index},\"now_ms\":${turn.nowMs},\"user_text\":\"${turn.userText.jsonEscape()}\",\"response\":\"${turn.response.jsonEscape()}\",\"outcome\":\"${turn.outcome}\",\"relationship_state\":\"${turn.relationshipState}\",\"tags\":${turn.tags.jsonArray()},\"diagnostics\":${observation.diagnostics.jsonObject()}}"
            },
            outputDirectory.resolve("bio.csv") to buildString {
                appendLine("turn,now_ms,L,P,E,S,tau,V,M,F")
                observations.forEach { observation ->
                    val turn = observation.transcript
                    val bio = observation.bio
                    appendLine("${turn.index},${turn.nowMs},${bio.l},${bio.p},${bio.e},${bio.s},${bio.tau},${bio.v},${bio.m},${bio.f}")
                }
            }.trimEnd(),
            outputDirectory.resolve("relationship-events.jsonl") to observations.joinToString("\n") { observation ->
                val event = observation.relationshipEvent
                "{\"turn\":${event.turnIndex},\"now_ms\":${event.nowMs},\"events\":${event.events.jsonArray()},\"relationship_state\":\"${event.relationshipState}\",\"evolution_index\":${observation.state.evolutionIndex},\"omega\":${observation.state.omega}}"
            },
            outputDirectory.resolve("prompt-cache-manifest.jsonl") to observations.joinToString("\n") { observation ->
                val metrics = observation.cacheMetrics
                "{\"turn\":${observation.transcript.index},\"now_ms\":${observation.transcript.nowMs},\"trace_id\":\"${observation.trace.traceId}\",\"trace_stage\":\"${observation.trace.stage}\",\"input_tokens\":${metrics.inputTokens},\"cached_input_tokens\":${metrics.cachedInputTokens},\"cache_read_rate\":${metrics.cacheHitRate}}"
            },
            outputDirectory.resolve("evaluation-report.md") to buildString {
                appendLine("# Relationship Evaluation")
                appendLine()
                appendLine("- Turns: ${turns.size}")
                appendLine("- Cache read rate: ${cacheReadRate ?: "unobservable"}")
                appendLine("- Relationship events: ${relationshipEvents.count { it.events.isNotEmpty() }}")
            }.trimEnd(),
        )
        files.forEach { (path, content) -> Files.writeString(path, "$content\n", StandardCharsets.UTF_8) }
        ExportedArtifacts(files.map { it.first }, files.associate { (path, _) -> path.fileName.toString() to path.sha256() })
    }
}

data class ExportedArtifacts(
    val files: List<Path>,
    val fingerprints: Map<String, String>,
)

class RelationshipLongRunHarness(
    private val clock: AdvancingRuntimeClock,
    private val pipeline: RelationshipEvaluationPipeline,
) {
    suspend fun run(scenario: RelationshipScenario): LongRunResult {
        val observations = scenario.turns.mapIndexed { index, scenarioTurn ->
            clock.advanceBy(scenarioTurn.advanceMs)
            pipeline.evaluate(EvaluationRequest(index + 1, clock.nowMs(), scenarioTurn, scenario.variant))
        }
        val metrics = observations.map(EvaluationObservation::cacheMetrics)
        return LongRunResult(
            observations = observations,
            turns = observations.map(EvaluationObservation::transcript),
            bioSnapshots = observations.map(EvaluationObservation::bio),
            cacheReadRate = metrics.cacheReadRate(),
            relationshipEvents = observations.map(EvaluationObservation::relationshipEvent),
            promptCacheManifest = observations.map { observation ->
                PromptCacheManifestEntry(observation.transcript.index, observation.transcript.nowMs, observation.trace.traceId, observation.cacheMetrics)
            },
        )
    }

    companion object {
        fun fake(variant: EvaluationVariant = EvaluationVariant.A, startMs: Long = 0L): RelationshipLongRunHarness =
            RelationshipLongRunHarness(DeterministicRuntimeClock(startMs), fakePipeline(variant))

        fun fakePipeline(variant: EvaluationVariant): RelationshipEvaluationPipeline = FakeEvaluationPipeline(variant)
    }
}

interface AdvancingRuntimeClock : RuntimeClock {
    fun advanceBy(milliseconds: Long)
}

class DeterministicRuntimeClock(
    private var currentMs: Long = 0L,
) : AdvancingRuntimeClock {
    override fun nowMs(): Long = currentMs

    override fun advanceBy(milliseconds: Long) {
        require(milliseconds >= 0L)
        currentMs += milliseconds
    }
}

private class FakeEvaluationPipeline(
    private val variant: EvaluationVariant,
) : RelationshipEvaluationPipeline {
    private var relationshipState = "STRANGERS"

    override suspend fun evaluate(request: EvaluationRequest): EvaluationObservation {
        val tags = request.scenarioTurn.tags
        val outcome = outcomeFor(tags)
        relationshipState = nextRelationshipState(tags)
        val turn = EvaluatedTurn(
            request.index, request.nowMs, request.scenarioTurn.userText, tags,
            responseFor(outcome), outcome, relationshipState,
        )
        return EvaluationObservation(
            transcript = turn,
            bio = deterministicBio(request.index),
            diagnostics = mapOf(
                "variant" to variant.name,
                "outcome" to outcome,
                "source" to if ("heartbeat" in tags) "HEARTBEAT" else "USER",
            ),
            trace = EvaluationTrace("${variant.name.lowercase()}-turn-${request.index}", "evaluation-pipeline"),
            cacheMetrics = LlmCacheMetrics(1_000L, if (request.index == 1) 0L else 900L),
            relationshipEvent = RelationshipEvent(request.index, request.nowMs, tags.intersect(relationshipEventTags), relationshipState),
            state = EvaluationState(relationshipState, request.index.toLong(), omegaFor(request.index)),
        )
    }

    private fun outcomeFor(tags: Set<String>): String = when {
        "negative" in tags -> "invitation_declined_without_pressure"
        "hot-romance" in tags -> "mutual_romance_reciprocated"
        "boundary" in tags -> "boundary_respected"
        "conflict" in tags -> "conflict_acknowledged"
        "repair" in tags -> "repair_completed"
        "silence" in tags -> "silence_observed"
        "heartbeat" in tags -> "heartbeat_delivered"
        "confession" in tags -> "confession_recorded"
        "acceptance" in tags -> "acceptance_recorded"
        "restart" in tags -> "relationship_continuity_preserved"
        else -> "daily_continuity_recorded"
    }

    private fun nextRelationshipState(tags: Set<String>): String = when {
        "confession" in tags -> "CONFESSED"
        "acceptance" in tags -> "COUPLE"
        else -> relationshipState
    }

    private fun responseFor(outcome: String): String = "${variant.name}:$outcome"

    private fun deterministicBio(index: Int): BioVector {
        val offset = (index % 20) / 100.0f
        return BioVector(0.5f + offset, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f)
    }

    private fun omegaFor(index: Int): Float = (index / 10) / 100.0f
}

private val relationshipEventTags = setOf(
    "confession", "acceptance", "restart", "hot-romance", "chores", "boundary", "conflict", "repair", "silence", "heartbeat",
)

private fun List<LlmCacheMetrics>.cacheReadRate(): Double? {
    val inputTokens = sumOf(LlmCacheMetrics::inputTokens)
    return inputTokens.takeIf { it > 0L }?.let { sumOf(LlmCacheMetrics::cachedInputTokens).toDouble() / it }
}

private fun String.jsonEscape(): String = replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

private fun Set<String>.jsonArray(): String = sorted().joinToString(prefix = "[", postfix = "]") { "\"${it.jsonEscape()}\"" }

private fun Map<String, String>.jsonObject(): String = entries.sortedBy(Map.Entry<String, String>::key)
    .joinToString(prefix = "{", postfix = "}") { (key, value) -> "\"${key.jsonEscape()}\":\"${value.jsonEscape()}\"" }

private fun Path.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(Files.readAllBytes(this))
    .joinToString("") { "%02x".format(it) }
