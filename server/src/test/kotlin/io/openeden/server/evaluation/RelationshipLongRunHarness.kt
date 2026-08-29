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
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
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
    val scenarioFingerprint: String,
    val observations: List<EvaluationObservation>,
    val turns: List<EvaluatedTurn>,
    val bioSnapshots: List<BioVector>,
    val cacheReadRate: Double?,
    val relationshipEvents: List<RelationshipEvent>,
    val promptCacheManifest: List<PromptCacheManifestEntry>,
) {
    suspend fun exportTo(
        outputDirectory: Path,
        releaseReport: PairwiseEvaluation.ReleaseReport = PairwiseEvaluation.ReleaseReport.synthetic(scenarioFingerprint),
    ): ExportedArtifacts = withContext(Dispatchers.IO) {
        require(
            releaseReport.evidenceKind == PairwiseEvaluation.EvidenceKind.SYNTHETIC_FIXTURE &&
                releaseReport.releaseDecision() == PairwiseEvaluation.ReleaseDecision.SYNTHETIC_ONLY &&
                releaseReport.productionProvenance == null &&
                releaseReport.syntheticFixture?.nonProduction == true &&
                releaseReport.syntheticFixture.personaFree,
        ) { "RelationshipLongRunHarness exports synthetic evidence only" }
        require(
            cacheReadRate == null &&
                observations.all { it.cacheMetrics.availability == io.openeden.llm.CacheMetricAvailability.UNOBSERVABLE } &&
                promptCacheManifest.all { it.metrics.availability == io.openeden.llm.CacheMetricAvailability.UNOBSERVABLE },
        ) { "Synthetic relationship evaluation cannot export provider cache evidence" }
        require(releaseReport.scenarioFingerprint == scenarioFingerprint) {
            "Release report scenario fingerprint must match the exported run"
        }
        Files.createDirectories(outputDirectory)
        val persistedReleaseReport = releaseReport.persisted()
        val files = listOf(
            outputDirectory.resolve("transcript.jsonl") to observations.joinToString("\n") { observation ->
                val turn = observation.transcript
                "{\"turn\":${turn.index},\"now_ms\":${turn.nowMs},\"user_text\":\"${turn.userText.jsonEscape()}\",\"response\":\"${turn.response.jsonEscape()}\",\"outcome\":\"${turn.outcome}\",\"relationship_state\":\"${turn.relationshipState}\",\"tags\":${turn.tags.jsonArray()},\"diagnostics\":${observation.diagnostics.jsonObject()}}"
            },
            outputDirectory.resolve("bio.csv") to buildString {
                appendLine("turn,now_ms,L,P,E,S,tau,V,M,F,trace_stage,diagnostics,evolution_index,omega")
                observations.forEach { observation ->
                    val turn = observation.transcript
                    val bio = observation.bio
                    listOf(
                        turn.index.toString(),
                        turn.nowMs.toString(),
                        bio.l.toString(),
                        bio.p.toString(),
                        bio.e.toString(),
                        bio.s.toString(),
                        bio.tau.toString(),
                        bio.v.toString(),
                        bio.m.toString(),
                        bio.f.toString(),
                        observation.trace.stage,
                        observation.diagnostics.csvDiagnostics(),
                        observation.state.evolutionIndex.toString(),
                        observation.state.omega.toString(),
                    ).joinToString(",") { it.csvField() }.also(::appendLine)
                }
            }.trimEnd(),
            outputDirectory.resolve("relationship-events.jsonl") to observations.joinToString("\n") { observation ->
                val event = observation.relationshipEvent
                "{\"turn\":${event.turnIndex},\"now_ms\":${event.nowMs},\"events\":${event.events.jsonArray()},\"relationship_state\":\"${event.relationshipState}\",\"evolution_index\":${observation.state.evolutionIndex},\"omega\":${observation.state.omega}}"
            },
            outputDirectory.resolve("prompt-cache-manifest.jsonl") to observations.joinToString("\n") { observation ->
                val metrics = observation.cacheMetrics
                val cacheReadRate = metrics.cacheHitRate.takeIf {
                    metrics.availability == io.openeden.llm.CacheMetricAvailability.REPORTED
                }
                "{\"turn\":${observation.transcript.index},\"now_ms\":${observation.transcript.nowMs},\"trace_id\":\"${observation.trace.traceId}\",\"trace_stage\":\"${observation.trace.stage}\",\"availability\":\"${metrics.availability}\",\"input_tokens\":${metrics.inputTokens},\"cached_input_tokens\":${metrics.cachedInputTokens},\"cache_read_rate\":${cacheReadRate ?: "null"}}"
            },
            outputDirectory.resolve("evaluation-report.md") to buildString {
                appendLine("# Relationship Evaluation")
                appendLine()
                appendLine("- Evidence kind: ${persistedReleaseReport.evidenceKind}")
                appendLine("- Release decision: ${persistedReleaseReport.releaseDecision}")
                appendLine("- Turns: ${turns.size}")
                appendLine("- Cache read rate: ${cacheReadRate ?: "unobservable"}")
                appendLine("- Relationship events: ${relationshipEvents.count { it.events.isNotEmpty() }}")
            }.trimEnd(),
            outputDirectory.resolve("release-gate-report.json") to releaseReportJson.encodeToString(persistedReleaseReport),
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
            scenarioFingerprint = scenario.fingerprint(),
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
        require(request.variant == variant) { "Fake pipeline variant must match request variant" }
        val tags = request.scenarioTurn.tags
        val outcome = outcomeFor(tags)
        relationshipState = nextRelationshipState(tags)
        val turn = EvaluatedTurn(
            request.index, request.nowMs, request.scenarioTurn.userText, tags,
            responseFor(outcome, request.variant), outcome, relationshipState,
        )
        return EvaluationObservation(
            transcript = turn,
            bio = deterministicBio(request.index),
            diagnostics = mapOf(
                "evaluation_profile" to profileFor(request.variant),
                "outcome" to outcome,
                "source" to if ("heartbeat" in tags) "HEARTBEAT" else "USER",
            ),
            trace = EvaluationTrace("${variant.name.lowercase()}-turn-${request.index}", traceStageFor(request.variant)),
            cacheMetrics = cacheMetricsFor(request.index, request.variant),
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

    private fun responseFor(outcome: String, variant: EvaluationVariant): String = when (variant) {
        EvaluationVariant.A -> "baseline evaluation: $outcome"
        EvaluationVariant.B -> "candidate evaluation: $outcome"
    }

    private fun profileFor(variant: EvaluationVariant): String = when (variant) {
        EvaluationVariant.A -> "baseline-stable-prefix"
        EvaluationVariant.B -> "candidate-dynamic-prefix"
    }

    private fun traceStageFor(variant: EvaluationVariant): String = when (variant) {
        EvaluationVariant.A -> "baseline-prompt-cache"
        EvaluationVariant.B -> "candidate-prompt-cache"
    }

    private fun cacheMetricsFor(index: Int, variant: EvaluationVariant): LlmCacheMetrics {
        require(index > 0 && variant == this.variant)
        return LlmCacheMetrics.Unobservable
    }

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
    if (isEmpty() || any { it.availability != io.openeden.llm.CacheMetricAvailability.REPORTED }) return null
    val inputTokens = sumOf(LlmCacheMetrics::inputTokens)
    return inputTokens.takeIf { it > 0L }?.let { sumOf(LlmCacheMetrics::cachedInputTokens).toDouble() / it }
}

private fun String.jsonEscape(): String = replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

private fun Set<String>.jsonArray(): String = sorted().joinToString(prefix = "[", postfix = "]") { "\"${it.jsonEscape()}\"" }

private fun Map<String, String>.jsonObject(): String = entries.sortedBy(Map.Entry<String, String>::key)
    .joinToString(prefix = "{", postfix = "}") { (key, value) -> "\"${key.jsonEscape()}\":\"${value.jsonEscape()}\"" }

private fun Map<String, String>.csvDiagnostics(): String = entries.sortedBy(Map.Entry<String, String>::key)
    .joinToString(";") { (key, value) -> "$key=$value" }

private fun String.csvField(): String = if (any { it == ',' || it == '"' || it == '\n' }) {
    "\"${replace("\"", "\"\"")}\""
} else {
    this
}

private fun Path.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(Files.readAllBytes(this))
    .joinToString("") { "%02x".format(it) }

private val releaseReportJson = Json {
    prettyPrint = true
}
